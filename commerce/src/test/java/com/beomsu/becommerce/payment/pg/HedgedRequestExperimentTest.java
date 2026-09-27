package com.beomsu.becommerce.payment.pg;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * hedged request(26절②, Google "The Tail at Scale") — 꼬리 지연 중복 요청의 p99 단축 대 PG 호출 증가.
 *
 * <p>{@link MultiInstancePgLimitExperimentTest}(#387)와 같은 방법론이다 — 로컬 {@link HttpServer} PG
 * 스텁(실 HTTP, 가상 스레드)과 실 {@link HttpClient}로 잰다. 그 테스트의 {@code GlobalLimitedPgClient}·
 * {@code AdaptiveLimitedPgClient}처럼, hedging도 프로덕션 {@code ResilientPgClient}는 건드리지 않고
 * 그것이 감쌀 delegate 자리에 들어가는 <b>테스트 전용</b> 데코레이터({@link HedgedPgClient})로만
 * 구현한다 — 26·27절 트레이드오프 실험은 결정된 기능이 아니라 비교 근거를 남기는 것이 목적이다.
 *
 * <p><b>멱등성은 이 실험이 검증하지 못한다.</b> hedge는 같은 커맨드(같은 Idempotency-Key)를 <b>동시에</b>
 * 두 번 보낸다. 기존 재전송(#395/#402)은 <b>순차적</b>(첫 시도가 끝난 뒤)이고 토스 문서가 그 경우의
 * 안전성만 밝힌다 — 동시(레이스) 요청을 PG가 내부적으로 어떻게 직렬화·중복 제거하는지는 PG 구현에
 * 달려 있고, 로컬 HTTP 스텁은 그 직렬화를 재현하지 못한다. 이 테스트는 <b>클라이언트 쪽 지연·호출 수
 * 트레이드오프만</b> 잰다(이슈 #415 "멱등성" 절 참고).
 *
 * <p>{@code ./gradlew experimentTest --tests '*HedgedRequest*'}로 실행한다(Docker 불필요, 로컬 HTTP만).
 */
@Tag("experiment")
class HedgedRequestExperimentTest {

    private static final int REQUESTS = 300;
    private static final int CONCURRENCY = 30;
    private static final long FAST_MS = 50;
    private static final long TAIL_MS = 2000;
    private static final double TAIL_RATE = 0.05;
    private static final long HEDGE_DELAY_MS = 100;

    @Test
    @DisplayName("꼬리 지연 PG 스텁(빠름 95%·느림 5%)에서 hedge 유무별 p50/p95/p99·PG 호출 수를 비교한다")
    void hedgeReducesTailLatencyAtSomeCallCost() throws Exception {
        Result noHedge = run("NOHEDGE", 0);
        Result hedge = run("HEDGE", HEDGE_DELAY_MS);

        System.out.printf("HEDGE-EXP cond=%s logical=%d pg_calls=%d call_ratio=%.3f p50=%dms p95=%dms p99=%dms%n",
                noHedge.name, noHedge.logicalRequests, noHedge.pgCalls, noHedge.callRatio(),
                noHedge.p50, noHedge.p95, noHedge.p99);
        System.out.printf("HEDGE-EXP cond=%s logical=%d pg_calls=%d call_ratio=%.3f p50=%dms p95=%dms p99=%dms"
                        + " hedge_fired=%d hedge_won_primary=%d hedge_won_hedge=%d%n",
                hedge.name, hedge.logicalRequests, hedge.pgCalls, hedge.callRatio(),
                hedge.p50, hedge.p95, hedge.p99, hedge.hedgeFired, hedge.hedgeWonPrimary, hedge.hedgeWonHedge);

        // 판정 기준(이슈 #415, 측정 전에 적음) — 조건이 실제로 섰다는 증거부터 확인한다.
        assertThat(hedge.hedgeFired).isGreaterThan(0);   // 1건도 안 걸렸다면 조건이 안 선 것 — 값을 믿을 수 없다
        assertThat(hedge.logicalRequests).isEqualTo(REQUESTS);
        assertThat(noHedge.logicalRequests).isEqualTo(REQUESTS);
    }

    private record Result(String name, int logicalRequests, int pgCalls, long p50, long p95, long p99,
                          int hedgeFired, int hedgeWonPrimary, int hedgeWonHedge) {
        double callRatio() {
            return (double) pgCalls / logicalRequests;
        }
    }

    private Result run(String name, long hedgeDelayMs) throws Exception {
        TailLatencyPgStub stub = new TailLatencyPgStub(FAST_MS, TAIL_MS, TAIL_RATE);
        try {
            HttpPgClient http = new HttpPgClient(stub.port());
            ExecutorService hedgeExecutor = Executors.newVirtualThreadPerTaskExecutor();
            PgClient client = hedgeDelayMs > 0 ? new HedgedPgClient(http, hedgeDelayMs, hedgeExecutor) : http;

            ExecutorService workers = Executors.newFixedThreadPool(CONCURRENCY);
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(REQUESTS);
            for (int i = 0; i < REQUESTS; i++) {
                workers.submit(() -> {
                    long began = System.nanoTime();
                    try {
                        client.approve(new PgApproveCommand(
                                "hedge-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), 10_000));
                    } catch (RuntimeException ignored) {
                        // 측정 목적 — 지연·호출 수만 본다. 결과 상태는 이 실험의 관심사가 아니다.
                    } finally {
                        latencies.add((System.nanoTime() - began) / 1_000_000);
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
            workers.shutdown();
            hedgeExecutor.shutdown();

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            int hedgeFired = client instanceof HedgedPgClient h ? h.fired.get() : 0;
            int hedgeWonPrimary = client instanceof HedgedPgClient h ? h.wonPrimary.get() : 0;
            int hedgeWonHedge = client instanceof HedgedPgClient h ? h.wonHedge.get() : 0;
            return new Result(name, latencies.size(), stub.totalCalls.get(),
                    percentile(sorted, 0.50), percentile(sorted, 0.95), percentile(sorted, 0.99),
                    hedgeFired, hedgeWonPrimary, hedgeWonHedge);
        } finally {
            stub.close();
        }
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    // --- PG 스텁: 요청마다 독립적으로 빠름/느림을 뽑는다(꼬리 지연 재현) ---

    private static final class TailLatencyPgStub {
        final HttpServer server;
        final long fastMs;
        final long tailMs;
        final double tailRate;
        final AtomicInteger totalCalls = new AtomicInteger();

        TailLatencyPgStub(long fastMs, long tailMs, double tailRate) throws Exception {
            this.fastMs = fastMs;
            this.tailMs = tailMs;
            this.tailRate = tailRate;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 2_000);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/v1/payments/confirm", ex -> {
                totalCalls.incrementAndGet();
                boolean tail = java.util.concurrent.ThreadLocalRandom.current().nextDouble() < this.tailRate;
                try {
                    Thread.sleep(tail ? this.tailMs : this.fastMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                byte[] body = "{\"status\":\"DONE\"}".getBytes();
                try {
                    ex.sendResponseHeaders(200, body.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(body);
                    }
                } catch (Exception ignored) {
                    // 클라이언트가 이미 다른 답(hedge 승자)을 받아 끊었을 수 있다 — PG는 처리를 마친다.
                } finally {
                    ex.close();
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void close() {
            server.stop(0);
        }
    }

    /** {@link MultiInstancePgLimitExperimentTest}의 것과 동일 — HTTP로 PG를 부른다. */
    private static final class HttpPgClient implements PgClient {
        private final HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
        private final URI uri;

        HttpPgClient(int port) {
            this.uri = URI.create("http://127.0.0.1:" + port + "/v1/payments/confirm");
        }

        @Override
        public PgApproveResult approve(PgApproveCommand command) {
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"" + command.orderNo() + "\"}")).build();
            try {
                HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() == 200) {
                    return PgApproveResult.success("CARD");
                }
                throw new IllegalStateException("PG " + res.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }

        @Override
        public PgCancelResult cancel(PgCancelCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PgQueryResult query(String paymentKey) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * hedged request 데코레이터 — 테스트 전용(위 클래스 javadoc 참고). 원 요청을 비동기로 보내고,
     * {@code hedgeDelayMs} 안에 안 끝나면 <b>같은 커맨드</b>로 중복 요청을 하나 더 보내 먼저 끝나는
     * 쪽을 쓴다. 진 쪽은 백그라운드에서 계속 돌게 두고(취소하지 않는다 — 이미 PG에 닿았을 수 있어
     * 취소해도 PG 쪽 처리는 안 멈춘다) 결과만 버린다.
     */
    private static final class HedgedPgClient implements PgClient {
        private final PgClient delegate;
        private final long hedgeDelayMs;
        private final ExecutorService executor;
        final AtomicInteger fired = new AtomicInteger();
        final AtomicInteger wonPrimary = new AtomicInteger();
        final AtomicInteger wonHedge = new AtomicInteger();

        HedgedPgClient(PgClient delegate, long hedgeDelayMs, ExecutorService executor) {
            this.delegate = delegate;
            this.hedgeDelayMs = hedgeDelayMs;
            this.executor = executor;
        }

        @Override
        public PgApproveResult approve(PgApproveCommand command) {
            CompletableFuture<PgApproveResult> primary = CompletableFuture.supplyAsync(
                    () -> delegate.approve(command), executor);
            try {
                return primary.get(hedgeDelayMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                fired.incrementAndGet();
                CompletableFuture<PgApproveResult> hedge = CompletableFuture.supplyAsync(
                        () -> delegate.approve(command), executor);
                AtomicLong winner = new AtomicLong(-1);
                CompletableFuture<PgApproveResult> race = CompletableFuture.anyOf(
                        primary.whenComplete((r, ex) -> winner.compareAndSet(-1, 0)),
                        hedge.whenComplete((r, ex) -> winner.compareAndSet(-1, 1))
                ).thenApply(o -> (PgApproveResult) o);
                try {
                    PgApproveResult result = race.get(30, TimeUnit.SECONDS);
                    if (winner.get() == 0) {
                        wonPrimary.incrementAndGet();
                    } else {
                        wonHedge.incrementAndGet();
                    }
                    return result;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(ie);
                } catch (ExecutionException | TimeoutException ex2) {
                    throw new IllegalStateException("hedge 레이스 실패", ex2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }

        @Override
        public PgCancelResult cancel(PgCancelCommand command) {
            return delegate.cancel(command);
        }

        @Override
        public PgQueryResult query(String paymentKey) {
            return delegate.query(paymentKey);
        }
    }
}
