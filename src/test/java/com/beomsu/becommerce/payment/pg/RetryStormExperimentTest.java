package com.beomsu.becommerce.payment.pg;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 실제 PG 대신 항상 실패하는 조회 더블을 사용해 재시도 예산이 동시 장애에서 어떻게 보이는지 측정한다.
 * 승인(쓰기) 재시도와 달리 조회만 bounded retry 대상이라는 설계 경계를 검증한다.
 */
@Tag("experiment")
class RetryStormExperimentTest {

    @Test
    @DisplayName("동시 조회 장애에서도 요청당 최대 3회로 재시도가 제한된다")
    void boundedRetryCapsFanoutDuringConcurrentBrownout() throws Exception {
        int requests = 200;
        int workers = 32;
        AtomicInteger delegateCalls = new AtomicInteger();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        PgClient alwaysDown = new PgClient() {
            @Override
            public PgApproveResult approve(PgApproveCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PgCancelResult cancel(PgCancelCommand command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PgQueryResult query(String paymentKey) {
                delegateCalls.incrementAndGet();
                throw new RuntimeException("synthetic PG brownout");
            }
        };

        ResilientPgClient client = new ResilientPgClient(alwaysDown, 0, registry);
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        long started = System.nanoTime();
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                calls.add(() -> {
                    try {
                        client.query("pk-" + Thread.currentThread().getId());
                        return false;
                    } catch (RuntimeException expected) {
                        return true;
                    }
                });
            }
            List<Future<Boolean>> futures = pool.invokeAll(calls);
            long elapsedMs = (System.nanoTime() - started) / 1_000_000;

            assertThat(futures).allSatisfy(future -> assertThat(future.get()).isTrue());
            assertThat(delegateCalls.get()).isBetween(1, requests * 3);
            assertThat(registry.counter("payment.pg.query.retry").count())
                    .isLessThanOrEqualTo(requests * 2);
            assertThat(registry.counter("payment.pg.query.retry.exhausted").count())
                    .isLessThanOrEqualTo(requests);

            System.out.printf(
                    "\n=== retry storm experiment ===%nrequests=%d workers=%d attempts=%d attempts_per_request=%.1f retry_events=%.0f exhausted=%.0f elapsed_ms=%d%n",
                    requests,
                    workers,
                    delegateCalls.get(),
                    (double) delegateCalls.get() / requests,
                    registry.counter("payment.pg.query.retry").count(),
                    registry.counter("payment.pg.query.retry.exhausted").count(),
                    elapsedMs
            );
        } finally {
            pool.shutdownNow();
        }
    }
}
