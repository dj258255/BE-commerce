package com.beomsu.becommerce.payment.pg;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 실패가 새 승인을 막는지, 서킷이 열려 보내지 않은 승인이 무엇으로 적히는지 센다(#372).
 *
 * <p>지연이 아니라 <b>건수</b>를 잰다. PG 는 스텁이고 승인 · 조회를 일정한 속도로 동시에 보낸다.
 * 이 파일은 {@link ResilientPgClient} 의 공개 API 만 쓴다. 그래서 같은 파일을 바꾸기 전 코드(서킷 하나)와
 * 바꾼 코드(서킷 셋)에 넣어 같은 조건으로 전후를 잰다.
 *
 * <pre>
 *   ./gradlew -p commerce experimentTest --tests '*CircuitSplitExperimentTest' \
 *       -Dcircuit.exp.seconds=60 -Dcircuit.exp.label=after -Dcircuit.exp.out=/tmp/out
 * </pre>
 *
 * <p>유령 미확정: PG 에 닿지 않았는데(스텁이 그 주문을 받은 적이 없다) 결과가 TIMEOUT(미확정)인 승인.
 */
@Tag("experiment")
class CircuitSplitExperimentTest {

    private static final int SECONDS = Integer.getInteger("circuit.exp.seconds", 60);
    private static final int QUERY_PER_SEC = 20;
    private static final int APPROVE_PER_SEC = 10;

    record Condition(String name, double queryFailRate, boolean approveDown) {
    }

    /** 조회 실패율을 주입하고, 승인을 받은 주문을 기록하는 PG 스텁. */
    static final class StubPg implements PgClient {
        private final double queryFailRate;
        private final boolean approveDown;
        private final Random random = new Random(42);
        final Set<String> reachedOrders = ConcurrentHashMap.newKeySet();
        final AtomicInteger approveCalls = new AtomicInteger();
        final AtomicInteger queryCalls = new AtomicInteger();
        final AtomicLong firstQueryFailureNanos = new AtomicLong();

        StubPg(double queryFailRate, boolean approveDown) {
            this.queryFailRate = queryFailRate;
            this.approveDown = approveDown;
        }

        @Override
        public PgApproveResult approve(PgApproveCommand command) {
            reachedOrders.add(command.orderNo());
            approveCalls.incrementAndGet();
            if (approveDown) {
                throw new RuntimeException("PG 승인 장애");
            }
            return PgApproveResult.success("CARD");
        }

        @Override
        public PgCancelResult cancel(PgCancelCommand command) {
            return new PgCancelResult("tx");
        }

        @Override
        public PgQueryResult query(String paymentKey) {
            queryCalls.incrementAndGet();
            boolean fail;
            synchronized (random) {
                fail = random.nextDouble() < queryFailRate;
            }
            if (fail) {
                firstQueryFailureNanos.compareAndSet(0, System.nanoTime());
                throw new RuntimeException("PG 조회 장애");
            }
            return new PgQueryResult(PgPaymentStatus.APPROVED, "CARD");
        }
    }

    record Result(String condition, int approvals, int reached, int reachedSuccess, int reachedUnknown,
                  int notReachedFailed, int notReachedUnknown, int queries, int queryOk, int queryFailed,
                  int queryBlocked, int queryDelegateCalls, long queryCircuitOpenMs) {

        String json() {
            return """
                    {"condition":"%s","approvals":%d,"reached":%d,"reached_success":%d,"reached_unknown":%d,\
                    "not_reached_failed":%d,"ghost_unknown":%d,"queries":%d,"query_ok":%d,"query_failed":%d,\
                    "query_blocked":%d,"query_delegate_calls":%d,"query_circuit_open_ms":%d}"""
                    .formatted(condition, approvals, reached, reachedSuccess, reachedUnknown, notReachedFailed,
                            notReachedUnknown, queries, queryOk, queryFailed, queryBlocked, queryDelegateCalls,
                            queryCircuitOpenMs);
        }
    }

    @Test
    @DisplayName("조회 실패율 10 · 30 · 100% 와 승인 전면 장애에서 승인 · 조회 결과를 센다")
    void countOutcomes() throws Exception {
        List<Condition> conditions = List.of(
                new Condition("Q10", 0.10, false),
                new Condition("Q30", 0.30, false),
                new Condition("Q100", 1.00, false),
                new Condition("A100", 0.00, true));
        String label = System.getProperty("circuit.exp.label", "run");
        List<Result> results = new ArrayList<>();
        for (Condition c : conditions) {
            Result r = run(c);
            results.add(r);
            System.out.printf("%n[%s] %s  승인 %d (닿음 %d: 성공 %d · 미확정 %d | 안 닿음: 확정 실패 %d · 유령 미확정 %d)"
                            + "  조회 %d (성공 %d · 실패 %d · 서킷에 막힘 %d, PG 에 간 조회 %d, 조회 서킷 열림 %dms)%n",
                    label, r.condition(), r.approvals(), r.reached(), r.reachedSuccess(), r.reachedUnknown(),
                    r.notReachedFailed(), r.notReachedUnknown(), r.queries(), r.queryOk(), r.queryFailed(),
                    r.queryBlocked(), r.queryDelegateCalls(), r.queryCircuitOpenMs());
        }

        String out = System.getProperty("circuit.exp.out");
        if (out != null) {
            Path dir = Path.of(out);
            Files.createDirectories(dir);
            StringBuilder json = new StringBuilder("{\"label\":\"" + label + "\",\"seconds\":" + SECONDS
                    + ",\"query_per_sec\":" + QUERY_PER_SEC + ",\"approve_per_sec\":" + APPROVE_PER_SEC
                    + ",\"results\":[");
            for (int i = 0; i < results.size(); i++) {
                json.append(i == 0 ? "" : ",").append(results.get(i).json());
            }
            Files.writeString(dir.resolve("circuit-" + label + ".json"), json.append("]}\n").toString(),
                    StandardCharsets.UTF_8);
        }
        for (Result r : results) {
            assertThat(r.approvals()).as("부하가 실제로 돌았다").isGreaterThan(SECONDS * APPROVE_PER_SEC / 2);
        }
    }

    private Result run(Condition c) throws InterruptedException {
        StubPg pg = new StubPg(c.queryFailRate(), c.approveDown());
        ResilientPgClient client = new ResilientPgClient(pg, 0, new SimpleMeterRegistry(), 3);   // 상한 없음: 서킷만 본다

        AtomicInteger approvals = new AtomicInteger();
        AtomicInteger reachedSuccess = new AtomicInteger();
        AtomicInteger reachedUnknown = new AtomicInteger();
        AtomicInteger reachedOther = new AtomicInteger();
        AtomicInteger notReachedFailed = new AtomicInteger();
        AtomicInteger notReachedUnknown = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        AtomicInteger queryOk = new AtomicInteger();
        AtomicInteger queryFailed = new AtomicInteger();
        AtomicInteger queryBlocked = new AtomicInteger();
        AtomicLong firstBlockedNanos = new AtomicLong();
        AtomicInteger seq = new AtomicInteger();

        ExecutorService workers = Executors.newFixedThreadPool(32);
        ScheduledExecutorService ticks = Executors.newScheduledThreadPool(2);
        ticks.scheduleAtFixedRate(() -> workers.submit(() -> {
            String orderNo = c.name() + "-ord-" + seq.incrementAndGet();
            approvals.incrementAndGet();
            PgApproveResult r = client.approve(new PgApproveCommand("pk-" + orderNo, orderNo, 10_000));
            boolean reached = pg.reachedOrders.contains(orderNo);
            if (reached) {
                switch (r.outcome()) {
                    case SUCCESS -> reachedSuccess.incrementAndGet();
                    case TIMEOUT -> reachedUnknown.incrementAndGet();
                    default -> reachedOther.incrementAndGet();
                }
            } else if (r.outcome() == PgOutcome.TIMEOUT) {
                notReachedUnknown.incrementAndGet();
            } else {
                notReachedFailed.incrementAndGet();
            }
        }), 0, 1000 / APPROVE_PER_SEC, TimeUnit.MILLISECONDS);
        ticks.scheduleAtFixedRate(() -> workers.submit(() -> {
            queries.incrementAndGet();
            try {
                client.query("pk-q-" + seq.incrementAndGet());
                queryOk.incrementAndGet();
            } catch (CallNotPermittedException open) {
                queryBlocked.incrementAndGet();
                firstBlockedNanos.compareAndSet(0, System.nanoTime());
            } catch (RuntimeException failed) {
                queryFailed.incrementAndGet();
            }
        }), 0, 1000 / QUERY_PER_SEC, TimeUnit.MILLISECONDS);

        Thread.sleep(SECONDS * 1000L);
        ticks.shutdownNow();
        workers.shutdown();
        assertThat(workers.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        long openMs = (firstBlockedNanos.get() == 0 || pg.firstQueryFailureNanos.get() == 0) ? -1
                : TimeUnit.NANOSECONDS.toMillis(firstBlockedNanos.get() - pg.firstQueryFailureNanos.get());
        int reached = reachedSuccess.get() + reachedUnknown.get() + reachedOther.get();
        return new Result(c.name(), approvals.get(), reached, reachedSuccess.get(), reachedUnknown.get(),
                notReachedFailed.get(), notReachedUnknown.get(), queries.get(), queryOk.get(), queryFailed.get(),
                queryBlocked.get(), pg.queryCalls.get(), openMs);
    }
}
