package com.beomsu.pay.payment.pg;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientPgClientTest {

    /** 장애를 주입할 수 있는 PG 더블. */
    static class FlakyPgClient implements PgClient {
        final AtomicInteger approveCalls = new AtomicInteger();
        final AtomicInteger queryCalls = new AtomicInteger();
        RuntimeException approveError;
        int queryFailuresRemaining;
        PgPaymentStatus queryStatus = PgPaymentStatus.APPROVED;

        @Override
        public PgApproveResult approve(PgApproveCommand c) {
            approveCalls.incrementAndGet();
            if (approveError != null) throw approveError;
            return PgApproveResult.success("CARD");
        }

        @Override
        public PgCancelResult cancel(PgCancelCommand c) {
            return new PgCancelResult("tx");
        }

        @Override
        public PgQueryResult query(String k) {
            queryCalls.incrementAndGet();
            if (queryFailuresRemaining > 0) {
                queryFailuresRemaining--;
                throw new RuntimeException("일시적 조회 실패");
            }
            return new PgQueryResult(queryStatus, queryStatus == PgPaymentStatus.APPROVED ? "CARD" : null);
        }
    }

    @Test
    @DisplayName("PG 승인이 예외를 던져도 UNKNOWN(TIMEOUT)으로 돌린다 — 실패로 단정하지 않는다")
    void approveExceptionBecomesUnknown() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.approveError = new RuntimeException("PG 연결 실패");
        ResilientPgClient client = new ResilientPgClient(flaky);

        PgApproveResult result = client.approve(new PgApproveCommand("pk", "order-1", 10_000));

        assertThat(result.outcome()).isEqualTo(PgOutcome.TIMEOUT);
    }

    @Test
    @DisplayName("승인은 재시도하지 않는다 — 멱등키 없는 재시도는 이중결제 위험")
    void approveIsNotRetried() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.approveError = new RuntimeException("PG 오류");
        ResilientPgClient client = new ResilientPgClient(flaky);

        client.approve(new PgApproveCommand("pk", "order-1", 10_000));

        assertThat(flaky.approveCalls.get()).isEqualTo(1); // 딱 한 번만 호출
    }

    @Test
    @DisplayName("PG 장애가 지속되면 서킷이 OPEN된다 — 이후엔 PG를 호출하지 않고 즉시 폴백")
    void circuitOpensAfterRepeatedFailures() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.approveError = new RuntimeException("PG 다운");
        ResilientPgClient client = new ResilientPgClient(flaky);

        // 최소 호출 수(3) 이상 실패시켜 서킷을 연다
        for (int i = 0; i < 5; i++) {
            client.approve(new PgApproveCommand("pk" + i, "order", 10_000));
        }
        int callsBeforeOpen = flaky.approveCalls.get();

        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // 서킷이 열린 뒤 추가 호출들은 PG에 닿지 않는다(폴백만)
        for (int i = 0; i < 3; i++) {
            PgApproveResult r = client.approve(new PgApproveCommand("pkX", "order", 10_000));
            assertThat(r.outcome()).isEqualTo(PgOutcome.TIMEOUT);
        }
        assertThat(flaky.approveCalls.get()).isEqualTo(callsBeforeOpen); // 델리게이트 호출 증가 없음
    }

    @Test
    @DisplayName("조회는 일시 실패 시 재시도로 흡수한다 (읽기라 안전)")
    void queryRetriesTransientFailure() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.queryFailuresRemaining = 2;          // 두 번 실패 후 성공
        ResilientPgClient client = new ResilientPgClient(flaky);

        PgQueryResult result = client.query("pk");

        assertThat(result.isApproved()).isTrue();
        assertThat(flaky.queryCalls.get()).isEqualTo(3); // 2회 실패 + 1회 성공
    }

    @Test
    @DisplayName("조회 재시도는 무한하지 않다 — maxAttempts(3) 소진 후 예외를 전파한다")
    void queryRetryIsBounded() {
        FlakyPgClient flaky = new FlakyPgClient();
        flaky.queryFailuresRemaining = 99;         // 계속 실패 (재시도로도 못 넘김)
        ResilientPgClient client = new ResilientPgClient(flaky);

        // 재시도가 무한 루프가 아니라 정해진 횟수만 시도하고 포기한다(복구 배치가 다음 주기에 다시 잡는다).
        assertThatThrownBy(() -> client.query("pk"))
                .isInstanceOf(RuntimeException.class);
        assertThat(flaky.queryCalls.get()).isEqualTo(3); // maxAttempts=3 만큼만 시도
    }

    @Test
    @DisplayName("정상 PG에서는 승인이 그대로 성공하고 델리게이트를 정확히 1번 호출한다 — 기준선")
    void approveSucceedsWithoutOverhead() {
        FlakyPgClient healthy = new FlakyPgClient();   // approveError 없음 → 항상 성공
        ResilientPgClient client = new ResilientPgClient(healthy);

        PgApproveResult result = client.approve(new PgApproveCommand("pk", "order-1", 10_000));

        assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCESS);
        assertThat(result.method()).isEqualTo("CARD");
        assertThat(healthy.approveCalls.get()).isEqualTo(1);
        assertThat(client.circuitBreaker().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("동시 호출 상한을 넘으면 승인을 시도하지 않고 확정 실패로 돌린다 — 미확정이 아니다")
    void overLimitIsFailedNotUnknown() throws Exception {
        // 지연 PG. permit 하나를 붙잡고 있는 동안 다음 호출이 상한에 걸린다.
        var gate = new java.util.concurrent.CountDownLatch(1);
        var entered = new java.util.concurrent.CountDownLatch(1);
        PgClient slow = new FlakyPgClient() {
            @Override
            public PgApproveResult approve(PgApproveCommand c) {
                entered.countDown();
                try {
                    gate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return PgApproveResult.success("CARD");
            }
        };
        ResilientPgClient client = new ResilientPgClient(slow, 1);

        Thread holder = new Thread(() -> client.approve(new PgApproveCommand("pk-1", "order-1", 10_000)));
        holder.start();
        entered.await();

        PgApproveResult overflow = client.approve(new PgApproveCommand("pk-2", "order-2", 10_000));

        // 요청이 PG 에 닿지 않은 것이 보장되므로 실패로 확정해도 안전하다.
        // 여기서 미확정을 돌리면 복구 배치가 조회할 대상이 없는 유령 미확정이 생긴다.
        assertThat(overflow.outcome()).isEqualTo(PgOutcome.FAILED);
        assertThat(overflow.failReason()).contains("상한");

        gate.countDown();
        holder.join();

        // permit 이 반납되어 다음 호출은 정상으로 나간다.
        assertThat(client.approve(new PgApproveCommand("pk-3", "order-3", 10_000)).outcome())
                .isEqualTo(PgOutcome.SUCCESS);
    }

    @Test
    @DisplayName("상한을 0 으로 두면 동시 호출을 막지 않는다 — 스프링 기본값은 40 이다")
    void noLimitWhenZero() {
        FlakyPgClient flaky = new FlakyPgClient();
        ResilientPgClient client = new ResilientPgClient(flaky);
        for (int i = 0; i < 50; i++) {
            assertThat(client.approve(new PgApproveCommand("pk" + i, "order", 10_000)).outcome())
                    .isEqualTo(PgOutcome.SUCCESS);
        }
        assertThat(flaky.approveCalls.get()).isEqualTo(50);
    }
}
