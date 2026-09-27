package com.beomsu.becommerce.payment.pg;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.netflix.concurrency.limits.Limiter;
import com.netflix.concurrency.limits.Limit;
import com.netflix.concurrency.limits.limit.Gradient2Limit;
import com.netflix.concurrency.limits.limit.VegasLimit;
import com.netflix.concurrency.limits.limiter.SimpleLimiter;
import com.sun.net.httpserver.HttpServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * API 를 여러 대 띄우면 PG 동시 호출 상한을 어떻게 나눌까(#387).
 *
 * <p>상한 40(ADR-022)은 인스턴스마다 걸린다. 여러 대면 PG 가 받는 동시 호출은 40 × 대수다. PG(가맹점 계약)에 동시 처리
 * 한도가 있으면 그 한도를 넘은 호출은 PG 안에서 줄을 서고, 읽기 타임아웃(5초)을 넘기면 결과 모름이 된다.
 *
 * <p>앱을 여러 대 띄우지 않고 실제 {@link ResilientPgClient} 를 인스턴스 수만큼 만들어 공유 PG 스텁 하나를 부르게 한다.
 * {@code prod} 프로필(실 PG 어댑터)은 청구 게이트웨이 구현이 없어 뜨지 않고 가짜 PG 는 인스턴스마다 따로라서다.
 *
 * <ul>
 *   <li>PG 스텁: 가맹점 동시 처리 한도 {@value #PG_CAPACITY}(가정). 넘은 요청은 PG 안에서 줄을 선다. 클라이언트가 끊어도
 *       PG 는 처리를 마친다(그래서 결과 모름이 생긴다)</li>
 *   <li>인스턴스: 워커 {@value #WORKERS} 개 풀 + {@code ResilientPgClient}. 요청은 라운드 로빈</li>
 *   <li>L40 인스턴스별 40 · SPLIT 한도를 대수로 나눔 · GLOBAL Redis 전역 한도 · ADAPT Netflix Gradient2(최대 40)</li>
 * </ul>
 *
 * <p>{@code ./gradlew experimentTest --tests '*MultiInstancePgLimit*'} 로 실행한다(1차 약 9분 · 2차 약 6분, Docker 필요). 결과는
 * {@code MULTIPG ...} 한 줄씩 남긴다.
 */
@Tag("experiment")
class MultiInstancePgLimitExperimentTest {

    private static final int PG_CAPACITY = 60;
    private static final int WORKERS = 100;
    private static final int LOCAL_CAP = 40;
    private static final int RATE = 50;                       // 전체 초당 승인
    private static final long NORMAL_MS = 200;
    private static final long SLOW_MS = 3_000;
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);
    private static final int WARM_S = 10;                     // 느린 조건도 앞 10초는 정상 PG 로 연다(적응형이 기준을 배우도록)
    private static final int MEASURE_S = 30;

    enum Strategy { L40, SPLIT, GLOBAL, GLOBAL_DOWN_CLOSED, GLOBAL_DOWN_OPEN, ADAPT, ADAPT_VEGAS, ADAPT_G2T }

    record Condition(String name, Strategy strategy, int instances, boolean slow, int splitOf) {
    }

    @Test
    @DisplayName("1차: 대수 · 전략 · PG 상태별로 PG 가 받는 동시 호출과 결과 모름")
    void round1() throws Exception {
        List<Condition> conditions = List.of(
                new Condition("normal-N1-L40", Strategy.L40, 1, false, 0),
                new Condition("normal-N3-L40", Strategy.L40, 3, false, 0),
                new Condition("normal-N3-SPLIT20", Strategy.SPLIT, 3, false, 3),
                new Condition("normal-N3-GLOBAL60", Strategy.GLOBAL, 3, false, 0),
                new Condition("normal-N3-ADAPT", Strategy.ADAPT, 3, false, 0),
                new Condition("slow-N1-L40", Strategy.L40, 1, true, 0),
                new Condition("slow-N2-L40", Strategy.L40, 2, true, 0),
                new Condition("slow-N3-L40", Strategy.L40, 3, true, 0),
                new Condition("slow-N3-SPLIT20", Strategy.SPLIT, 3, true, 3),
                new Condition("slow-N2of3-SPLIT20", Strategy.SPLIT, 2, true, 3),   // 한 대가 빠졌는데 한도는 20 그대로
                new Condition("slow-N3-GLOBAL60", Strategy.GLOBAL, 3, true, 0),
                new Condition("slow-N3-GLOBAL60-redisdown-closed", Strategy.GLOBAL_DOWN_CLOSED, 3, true, 0),
                new Condition("slow-N3-GLOBAL60-redisdown-open", Strategy.GLOBAL_DOWN_OPEN, 3, true, 0),
                new Condition("slow-N3-ADAPT", Strategy.ADAPT, 3, true, 0));
        for (Condition c : conditions) {
            run(c);
        }
    }

    /**
     * 2차(#387 댓글의 기준): 1차 기준이 성공 건수를 보지 않아 Gradient2 가 PG 의 느림을 혼잡으로 읽고 한도를 줄인 것을 못 걸렀다.
     * 적응형 두 설정을 더하고 비교 대상을 다시 잰다.
     */
    @Test
    @DisplayName("2차: 적응형 설정을 더해 가장 나쁜 경우의 성공 건수로 고른다")
    void round2() throws Exception {
        List<Condition> conditions = List.of(
                new Condition("normal-N3-ADAPT-VEGAS", Strategy.ADAPT_VEGAS, 3, false, 0),
                new Condition("normal-N3-ADAPT-G2T", Strategy.ADAPT_G2T, 3, false, 0),
                new Condition("slow-N1-L40", Strategy.L40, 1, true, 0),
                new Condition("slow-N3-SPLIT20", Strategy.SPLIT, 3, true, 3),
                new Condition("slow-N2of3-SPLIT20", Strategy.SPLIT, 2, true, 3),
                new Condition("slow-N3-GLOBAL60", Strategy.GLOBAL, 3, true, 0),
                new Condition("slow-N3-ADAPT", Strategy.ADAPT, 3, true, 0),
                new Condition("slow-N3-ADAPT-VEGAS", Strategy.ADAPT_VEGAS, 3, true, 0),
                new Condition("slow-N3-ADAPT-G2T", Strategy.ADAPT_G2T, 3, true, 0));
        for (Condition c : conditions) {
            run(c);
        }
    }

    // --- 한 조건 ---

    private void run(Condition c) throws Exception {
        PgStub pg = new PgStub(PG_CAPACITY);
        pg.serviceMs = NORMAL_MS;
        RedisClient redis = null;
        StatefulRedisConnection<String, String> conn = null;
        String globalKey = "pg:inflight:" + UUID.randomUUID();
        if (c.strategy == Strategy.GLOBAL) {
            redis = RedisClient.create(RedisURI.create(SharedContainers.redis().getHost(), SharedContainers.redis().getMappedPort(6379)));
            conn = redis.connect();
        } else if (c.strategy == Strategy.GLOBAL_DOWN_CLOSED || c.strategy == Strategy.GLOBAL_DOWN_OPEN) {
            RedisURI down = RedisURI.create("localhost", 1);   // 닫힌 포트: Redis 장애
            down.setTimeout(Duration.ofMillis(200));
            redis = RedisClient.create(down);
        }

        List<Instance> instances = new ArrayList<>();
        for (int i = 0; i < c.instances; i++) {
            PgClient http = new HttpPgClient(pg.port());
            PgClient delegate = switch (c.strategy) {
                case L40, SPLIT -> http;
                case GLOBAL -> new GlobalLimitedPgClient(http, conn.sync(), globalKey, PG_CAPACITY, true);
                case GLOBAL_DOWN_CLOSED -> new GlobalLimitedPgClient(http, redis, globalKey, PG_CAPACITY, true);
                case GLOBAL_DOWN_OPEN -> new GlobalLimitedPgClient(http, redis, globalKey, PG_CAPACITY, false);
                case ADAPT -> new AdaptiveLimitedPgClient(http,
                        Gradient2Limit.newBuilder().initialLimit(20).minLimit(1).maxConcurrency(LOCAL_CAP).build());
                case ADAPT_VEGAS -> new AdaptiveLimitedPgClient(http,
                        VegasLimit.newBuilder().initialLimit(20).maxConcurrency(LOCAL_CAP).build());
                case ADAPT_G2T -> new AdaptiveLimitedPgClient(http,
                        Gradient2Limit.newBuilder().initialLimit(20).minLimit(1).maxConcurrency(LOCAL_CAP).rttTolerance(2.0).build());
            };
            int cap = c.strategy == Strategy.SPLIT ? PG_CAPACITY / c.splitOf : LOCAL_CAP;
            instances.add(new Instance(new ResilientPgClient(delegate, cap)));
        }

        Tally warm = new Tally();
        Tally measured = new Tally();
        ScheduledExecutorService arrivals = Executors.newSingleThreadScheduledExecutor();
        AtomicInteger seq = new AtomicInteger();
        long periodMicros = 1_000_000L / RATE;
        int warmTicks = c.slow ? WARM_S * RATE : 0;
        int totalTicks = warmTicks + MEASURE_S * RATE;
        CountDownLatch submitted = new CountDownLatch(totalTicks);
        List<Future<?>> inflight = Collections.synchronizedList(new ArrayList<>());
        ScheduledFuture<?> ticker = arrivals.scheduleAtFixedRate(() -> {
            int n = seq.getAndIncrement();
            if (n >= totalTicks) return;
            if (c.slow && n == warmTicks) {
                pg.serviceMs = SLOW_MS;   // 느려지기 시작
                pg.resetMax();
                instances.forEach(Instance::resetMax);
            }
            Tally t = n < warmTicks ? warm : measured;
            Instance target = instances.get(n % instances.size());
            inflight.add(target.submit(t));
            submitted.countDown();
        }, 0, periodMicros, TimeUnit.MICROSECONDS);
        submitted.await();
        ticker.cancel(false);
        arrivals.shutdown();
        for (Future<?> f : new ArrayList<>(inflight)) f.get(60, TimeUnit.SECONDS);

        int appMax = instances.stream().mapToInt(i -> i.maxInFlight.get()).max().orElse(0);
        System.out.printf("MULTIPG cond=%s requests=%d success=%d rejected_local=%d rejected_global=%d rejected_adaptive=%d"
                        + " rejected_circuit=%d unknown=%d pg_max_inflight=%d pg_latency_p95_ms=%d app_max_inflight_per_instance=%d"
                        + " global_errors=%d%n",
                c.name, measured.total(), measured.success.get(), measured.rejectedLocal.get(), measured.rejectedGlobal.get(),
                measured.rejectedAdaptive.get(), measured.rejectedCircuit.get(), measured.unknown.get(), pg.maxInFlight.get(),
                pg.latencyP95(), appMax, measured.globalErrors.get());

        instances.forEach(Instance::close);
        pg.close();
        if (conn != null) conn.close();
        if (redis != null) redis.shutdown();
    }

    // --- 인스턴스 하나: 워커 풀 + ResilientPgClient ---

    private static final class Instance {
        final ResilientPgClient client;
        final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();

        Instance(ResilientPgClient client) {
            this.client = client;
        }

        Future<?> submit(Tally t) {
            return workers.submit(() -> {
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    PgApproveResult r = client.approve(new PgApproveCommand("mp-" + UUID.randomUUID(), "ORD-" + UUID.randomUUID(), 10_000));
                    t.record(r);
                } finally {
                    inFlight.decrementAndGet();
                }
            });
        }

        void resetMax() {
            maxInFlight.set(inFlight.get());
        }

        void close() {
            workers.shutdownNow();
        }
    }

    private static final class Tally {
        final AtomicInteger success = new AtomicInteger();
        final AtomicInteger unknown = new AtomicInteger();
        final AtomicInteger rejectedLocal = new AtomicInteger();
        final AtomicInteger rejectedGlobal = new AtomicInteger();
        final AtomicInteger rejectedAdaptive = new AtomicInteger();
        final AtomicInteger rejectedCircuit = new AtomicInteger();
        final AtomicInteger globalErrors = new AtomicInteger();
        final AtomicInteger other = new AtomicInteger();

        void record(PgApproveResult r) {
            switch (r.outcome()) {
                case SUCCESS -> success.incrementAndGet();
                case TIMEOUT -> unknown.incrementAndGet();
                case FAILED -> {
                    String why = String.valueOf(r.failReason());
                    if (why.contains("Redis")) {   // "전역 한도를 확인하지 못해"도 전역 한도 문구를 담아 먼저 가른다
                        rejectedGlobal.incrementAndGet();
                        globalErrors.incrementAndGet();
                    } else if (why.contains("동시 호출 상한")) rejectedLocal.incrementAndGet();
                    else if (why.contains("전역 한도")) rejectedGlobal.incrementAndGet();
                    else if (why.contains("적응형 한도")) rejectedAdaptive.incrementAndGet();
                    else if (why.contains("서킷")) rejectedCircuit.incrementAndGet();
                    else other.incrementAndGet();
                }
            }
        }

        int total() {
            return success.get() + unknown.get() + rejectedLocal.get() + rejectedGlobal.get() + rejectedAdaptive.get()
                    + rejectedCircuit.get() + other.get();
        }
    }

    // --- PG 쪽 ---

    /** 공유 PG. 동시 처리 한도를 넘은 요청은 줄을 서고, 클라이언트가 끊어도 처리를 마친다. */
    private static final class PgStub {
        final HttpServer server;
        final Semaphore capacity;
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger maxInFlight = new AtomicInteger();
        final List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
        volatile long serviceMs;
        volatile boolean recordLatency = true;

        PgStub(int capacity) throws Exception {
            this.capacity = new Semaphore(capacity, true);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 2_000);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/v1/payments/confirm", ex -> {
                long began = System.nanoTime();
                int now = inFlight.incrementAndGet();
                maxInFlight.accumulateAndGet(now, Math::max);
                try {
                    this.capacity.acquireUninterruptibly();
                    try {
                        Thread.sleep(serviceMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        this.capacity.release();
                    }
                    latencies.add((System.nanoTime() - began) / 1_000_000);
                    byte[] body = "{\"status\":\"DONE\"}".getBytes();
                    try {
                        ex.sendResponseHeaders(200, body.length);
                        try (OutputStream os = ex.getResponseBody()) {
                            os.write(body);
                        }
                    } catch (Exception ignored) {
                        // 클라이언트가 이미 끊었다. PG 는 처리를 마쳤다(결과 모름의 원인)
                    }
                } finally {
                    inFlight.decrementAndGet();
                    ex.close();
                }
            });
            server.start();
        }

        int port() {
            return server.getAddress().getPort();
        }

        void resetMax() {
            maxInFlight.set(inFlight.get());
            latencies.clear();
        }

        long latencyP95() {
            List<Long> copy;
            synchronized (latencies) {
                copy = new ArrayList<>(latencies);
            }
            if (copy.isEmpty()) return -1;
            Collections.sort(copy);
            return copy.get(Math.min(copy.size() - 1, (int) Math.ceil(0.95 * copy.size()) - 1));
        }

        void close() {
            server.stop(0);
        }
    }

    /** PG 를 HTTP 로 부른다. 읽기 타임아웃이면 예외를 던지고 ResilientPgClient 가 결과 모름으로 적는다. */
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
            HttpRequest req = HttpRequest.newBuilder(uri).timeout(READ_TIMEOUT)
                    .POST(HttpRequest.BodyPublishers.ofString("{\"orderId\":\"" + command.orderNo() + "\"}")).build();
            try {
                HttpResponse<Void> res = http.send(req, HttpResponse.BodyHandlers.discarding());
                if (res.statusCode() == 200) return PgApproveResult.success("CARD");
                throw new IllegalStateException("PG " + res.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);   // 타임아웃 포함. 승인이 났을 수 있다
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

    // --- 전략 ---

    /**
     * Redis 전역 한도. 인스턴스 상한(ResilientPgClient) 안쪽에서 전역 자리를 하나 더 얻는다. 자리를 못 얻으면 PG 에 보내지
     * 않았으므로 확정 실패다. Redis 에 닿지 못하면 닫힘(거절) 또는 열림(인스턴스 상한만)으로 간다.
     */
    private static final class GlobalLimitedPgClient implements PgClient {
        private static final String ACQUIRE = "local c = tonumber(redis.call('GET', KEYS[1]) or '0') "
                + "if c < tonumber(ARGV[1]) then redis.call('INCR', KEYS[1]) return 1 end return 0";
        private final PgClient delegate;
        private final Function<Void, RedisCommands<String, String>> commands;
        private final String key;
        private final int limit;
        private final boolean failClosed;

        GlobalLimitedPgClient(PgClient delegate, RedisCommands<String, String> cmd, String key, int limit, boolean failClosed) {
            this(delegate, v -> cmd, key, limit, failClosed);
        }

        GlobalLimitedPgClient(PgClient delegate, RedisClient down, String key, int limit, boolean failClosed) {
            this(delegate, v -> down.connect().sync(), key, limit, failClosed);   // 매번 연결을 시도한다(닫힌 포트라 곧 실패)
        }

        private GlobalLimitedPgClient(PgClient delegate, Function<Void, RedisCommands<String, String>> commands, String key,
                                      int limit, boolean failClosed) {
            this.delegate = delegate;
            this.commands = commands;
            this.key = key;
            this.limit = limit;
            this.failClosed = failClosed;
        }

        @Override
        public PgApproveResult approve(PgApproveCommand command) {
            RedisCommands<String, String> cmd;
            Long got;
            try {
                cmd = commands.apply(null);
                got = cmd.eval(ACQUIRE, ScriptOutputType.INTEGER, new String[]{key}, String.valueOf(limit));
            } catch (RuntimeException redisDown) {
                if (failClosed) return PgApproveResult.failed("Redis 장애: 전역 한도를 확인하지 못해 보내지 않음");
                return delegate.approve(command);   // 열림: 인스턴스 상한만 남는다
            }
            if (got == null || got == 0) return PgApproveResult.failed("PG 전역 한도 초과");
            try {
                return delegate.approve(command);
            } finally {
                cmd.decr(key);   // 프로세스가 여기 오기 전에 죽으면 자리가 샌다(운영이면 만료 달린 자리가 필요)
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

    /** Netflix concurrency-limits. 관측 지연이 늘면 한도를 줄이고 타임아웃은 과부하 신호(onDropped)로 준다. */
    private static final class AdaptiveLimitedPgClient implements PgClient {
        private final PgClient delegate;
        private final Limiter<Void> limiter;

        AdaptiveLimitedPgClient(PgClient delegate, Limit limit) {
            this.delegate = delegate;
            this.limiter = SimpleLimiter.newBuilder().limit(limit).build();
        }

        @Override
        public PgApproveResult approve(PgApproveCommand command) {
            Optional<Limiter.Listener> listener = limiter.acquire(null);
            if (listener.isEmpty()) return PgApproveResult.failed("PG 적응형 한도 초과");
            try {
                PgApproveResult r = delegate.approve(command);
                listener.get().onSuccess();
                return r;
            } catch (RuntimeException e) {
                listener.get().onDropped();
                throw e;
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
}
