package com.beomsu.becommerce.payment.pg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingPgClientTest {

    /** 결과/예외를 제어할 수 있는 PG 더블. */
    static class StubPg implements PgClient {
        final String name;
        final AtomicInteger approveCalls = new AtomicInteger();
        PgApproveResult result;                 // null이면 예외 던짐(불가)
        RuntimeException error;

        StubPg(String name) { this.name = name; }

        StubPg returns(PgApproveResult r) { this.result = r; return this; }

        /** 요청이 PG에 닿지도 못한 경우 — 연결 수립 실패. 넘겨도 안전한 유일한 실패다. */
        StubPg unreachable() {
            this.error = new PgUnreachableException(name + " 연결 실패", new java.net.ConnectException());
            return this;
        }

        /**
         * <b>실 어댑터가 미확정을 표현하는 방식</b> — 응답은 왔는데 승인 여부를 모른다.
         * {@code TossPgClient}는 일시 오류 응답을 예외로 던지고, 승인 금액 불일치도 예외다.
         * 더블이 "연결 실패"만 흉내 내면 이 경로를 영영 태우지 못한다.
         */
        StubPg respondsUncertain() {
            this.error = new IllegalStateException(name + " 일시 오류 응답 — 승인 여부 불명");
            return this;
        }

        @Override public PgApproveResult approve(PgApproveCommand c) {
            approveCalls.incrementAndGet();
            if (error != null) throw error;
            return result;
        }
        @Override public PgCancelResult cancel(PgCancelCommand c) { return new PgCancelResult("tx"); }
        @Override public PgQueryResult query(String k) { return new PgQueryResult(PgPaymentStatus.NOT_FOUND, null); }
    }

    private final PgApproveCommand cmd = new PgApproveCommand("pk", "order-1", 10_000);

    @Test
    @DisplayName("주 PG 성공 시 그것만 쓰고 보조 PG는 호출하지 않는다")
    void primarySucceeds() {
        StubPg toss = new StubPg("TOSS").returns(PgApproveResult.success("CARD"));
        StubPg nice = new StubPg("NICE").returns(PgApproveResult.success("CARD"));
        RoutingPgClient router = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("TOSS", toss, 10),
                RoutingPgClient.PgRoute.of("NICE", nice, 5)));

        PgApproveResult r = router.approve(cmd);

        assertThat(r.outcome()).isEqualTo(PgOutcome.SUCCESS);
        assertThat(toss.approveCalls.get()).isEqualTo(1);
        assertThat(nice.approveCalls.get()).isZero();     // failover 없음
    }

    @Test
    @DisplayName("요청이 PG에 닿지도 못하면 보조 PG로 failover")
    void failoverOnUnreachable() {
        StubPg toss = new StubPg("TOSS").unreachable();
        StubPg nice = new StubPg("NICE").returns(PgApproveResult.success("CARD"));
        RoutingPgClient router = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("TOSS", toss, 10),
                RoutingPgClient.PgRoute.of("NICE", nice, 5)));

        PgApproveResult r = router.approve(cmd);

        assertThat(r.outcome()).isEqualTo(PgOutcome.SUCCESS);
        assertThat(toss.approveCalls.get()).isEqualTo(1);  // 시도했고
        assertThat(nice.approveCalls.get()).isEqualTo(1);  // 넘어갔다
    }

    @Test
    @DisplayName("어댑터가 미확정을 예외로 던져도 failover하지 않는다 — 예외를 '요청 미도달'로 읽으면 이중 결제")
    void noFailoverWhenAdapterThrowsForUncertainOutcome() {
        StubPg toss = new StubPg("TOSS").respondsUncertain();
        StubPg nice = new StubPg("NICE").returns(PgApproveResult.success("CARD"));
        RoutingPgClient router = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("TOSS", toss, 10),
                RoutingPgClient.PgRoute.of("NICE", nice, 5)));

        PgApproveResult r = router.approve(cmd);

        assertThat(r.outcome())
                .as("응답이 왔으면 승인이 났을 수 있다 → 미확정으로 보존하고 복구 배치가 확정한다")
                .isEqualTo(PgOutcome.TIMEOUT);
        assertThat(toss.approveCalls.get()).isEqualTo(1);
        assertThat(nice.approveCalls.get())
                .as("여기서 넘기면 원 PG에서 승인된 결제에 카드가 한 번 더 긁힌다")
                .isZero();
    }

    @Test
    @DisplayName("카드 거절(FAILED)은 failover하지 않는다 — 다른 PG도 거절")
    void noFailoverOnBusinessDecline() {
        StubPg toss = new StubPg("TOSS").returns(PgApproveResult.failed("잔액부족"));
        StubPg nice = new StubPg("NICE").returns(PgApproveResult.success("CARD"));
        RoutingPgClient router = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("TOSS", toss, 10),
                RoutingPgClient.PgRoute.of("NICE", nice, 5)));

        PgApproveResult r = router.approve(cmd);

        assertThat(r.outcome()).isEqualTo(PgOutcome.FAILED);
        assertThat(nice.approveCalls.get()).isZero();      // failover 안 함
    }

    @Test
    @DisplayName("타임아웃(미확정)은 failover하지 않는다 — 이중결제 방지")
    void noFailoverOnTimeout() {
        StubPg toss = new StubPg("TOSS").returns(PgApproveResult.timeout("응답 없음"));
        StubPg nice = new StubPg("NICE").returns(PgApproveResult.success("CARD"));
        RoutingPgClient router = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("TOSS", toss, 10),
                RoutingPgClient.PgRoute.of("NICE", nice, 5)));

        PgApproveResult r = router.approve(cmd);

        assertThat(r.outcome()).isEqualTo(PgOutcome.TIMEOUT);
        assertThat(nice.approveCalls.get()).isZero();      // 이중결제 위험 → failover 금지
    }

    @Test
    @DisplayName("모든 PG에 요청이 닿지 못하면 UNKNOWN(TIMEOUT)으로 돌린다")
    void allUnreachableBecomesTimeout() {
        StubPg toss = new StubPg("TOSS").unreachable();
        StubPg nice = new StubPg("NICE").unreachable();
        RoutingPgClient router = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("TOSS", toss, 10),
                RoutingPgClient.PgRoute.of("NICE", nice, 5)));

        PgApproveResult r = router.approve(cmd);

        assertThat(r.outcome()).isEqualTo(PgOutcome.TIMEOUT);
    }

    @Test
    @DisplayName("주 PG 서킷이 열리면 건너뛰고 보조 PG로 간다")
    void skipsOpenCircuit() {
        StubPg toss = new StubPg("TOSS").unreachable();
        StubPg nice = new StubPg("NICE").returns(PgApproveResult.success("CARD"));
        RoutingPgClient router = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("TOSS", toss, 10),
                RoutingPgClient.PgRoute.of("NICE", nice, 5)));

        // TOSS를 반복 실패시켜 서킷을 연다(최소 3콜)
        for (int i = 0; i < 5; i++) router.approve(cmd);
        int tossCallsAfterOpen = toss.approveCalls.get();

        router.approve(cmd);   // 서킷 열린 뒤 한 번 더

        assertThat(toss.approveCalls.get()).isEqualTo(tossCallsAfterOpen); // TOSS 호출 증가 없음(건너뜀)
        assertThat(nice.approveCalls.get()).isGreaterThan(0);              // NICE가 처리
    }
}
