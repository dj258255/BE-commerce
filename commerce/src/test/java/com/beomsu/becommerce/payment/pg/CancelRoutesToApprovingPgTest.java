package com.beomsu.becommerce.payment.pg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 취소는 원 결제를 처리한 PG로 가야 한다.
 *
 * <p>이중화를 켜면 A가 닿지 않을 때 승인이 B로 넘어간다. 그런데 취소는 가용한 첫 PG로 가므로
 * 다시 A부터 시도한다. A는 그 결제를 모른다.
 *
 * <p>A가 살아 있으면 A는 "그런 거래 없음"을 <b>정상 응답</b>으로 돌려준다. 요청이 닿았으므로
 * failover 조건이 아니고, 그 응답이 그대로 취소 결과가 된다. <b>고객 돈이 B에 잡혀 있는데
 * 우리는 취소했다고 적는다.</b>
 *
 * <p>이것이 이중화를 기본으로 켜지 못한 이유다. README에도 "취소·조회의 원 결제 PG 라우팅은
 * 후속 과제"라고 적어 두었다.
 */
class CancelRoutesToApprovingPgTest {

    /** 자기가 승인한 결제만 아는 PG. 실제 PG가 그렇다. */
    static class ScopedPg implements PgClient {
        final String name;
        final AtomicInteger cancelCalls = new AtomicInteger();
        private final java.util.Set<String> approved = new java.util.HashSet<>();
        private boolean reachable = true;

        ScopedPg(String name) { this.name = name; }

        ScopedPg unreachable() { this.reachable = false; return this; }

        @Override public PgApproveResult approve(PgApproveCommand c) {
            if (!reachable) {
                throw new PgUnreachableException(name + " 연결 실패", new java.net.ConnectException());
            }
            approved.add(c.paymentKey());
            return PgApproveResult.success("CARD", name);
        }

        @Override public PgCancelResult cancel(PgCancelCommand c) {
            cancelCalls.incrementAndGet();
            if (!reachable) {
                throw new PgUnreachableException(name + " 연결 실패", new java.net.ConnectException());
            }
            if (!approved.contains(c.paymentKey())) {
                // 실제 PG가 돌려주는 것. 예외가 아니라 정상 응답이다
                throw new IllegalStateException(name + ": 그런 거래 없음 " + c.paymentKey());
            }
            return new PgCancelResult("cancel-tx-" + name);
        }

        @Override public PgQueryResult query(String k) {
            if (!reachable) {
                throw new PgUnreachableException(name + " 연결 실패", new java.net.ConnectException());
            }
            return approved.contains(k)
                    ? new PgQueryResult(PgPaymentStatus.APPROVED, "CARD")
                    : new PgQueryResult(PgPaymentStatus.NOT_FOUND, null);
        }
    }

    @Test
    @DisplayName("A가 닿지 않아 B가 승인하면, 승인한 PG 이름이 결과에 실린다")
    void 승인한_PG_이름이_결과에_실린다() {
        ScopedPg a = new ScopedPg("A").unreachable();
        ScopedPg b = new ScopedPg("B");

        RoutingPgClient routing = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("A", a, 10),
                RoutingPgClient.PgRoute.of("B", b, 5)));

        PgApproveResult result = routing.approve(new PgApproveCommand("pk-1", "order-1", 10_000));

        assertThat(result.outcome()).isEqualTo(PgOutcome.SUCCESS);
        // 이 값이 없으면 나중에 어디로 취소를 보내야 하는지 알 수 없다
        assertThat(result.provider()).isEqualTo("B");
    }

    @Test
    @DisplayName("승인한 PG를 지정하면 취소가 그 PG로 간다")
    void 지정하면_그_PG로_간다() {
        ScopedPg a = new ScopedPg("A");
        ScopedPg b = new ScopedPg("B");
        b.approve(new PgApproveCommand("pk-1", "order-1", 10_000));

        RoutingPgClient routing = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("A", a, 10),
                RoutingPgClient.PgRoute.of("B", b, 5)));

        PgCancelResult result = routing.cancel(
                new PgCancelCommand("pk-1", 10_000, "고객 요청", 1, "B"));

        assertThat(result.transactionKey()).isEqualTo("cancel-tx-B");
        // A 는 이 결제를 모른다. 물어보지도 않아야 한다
        assertThat(a.cancelCalls.get()).isZero();
        assertThat(b.cancelCalls.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("지정한 PG가 목록에 없으면 아무 PG로도 보내지 않는다")
    void 모르는_PG면_보내지_않는다() {
        ScopedPg a = new ScopedPg("A");
        ScopedPg b = new ScopedPg("B");

        RoutingPgClient routing = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("A", a, 10),
                RoutingPgClient.PgRoute.of("B", b, 5)));

        // 설정에서 빠진 PG로 승인된 옛 결제. 아무 데나 보내면 "그런 거래 없음"이 취소 결과가 된다
        assertThatThrownBy(() -> routing.cancel(
                new PgCancelCommand("pk-1", 10_000, "고객 요청", 1, "C")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("C");

        assertThat(a.cancelCalls.get()).isZero();
        assertThat(b.cancelCalls.get()).isZero();
    }

    @Test
    @DisplayName("지정이 없으면 예전처럼 첫 PG부터 시도한다")
    void 지정이_없으면_예전처럼_돈다() {
        // 이중화를 켜기 전에 만들어진 결제에는 승인 PG가 안 적혀 있다
        ScopedPg a = new ScopedPg("A");
        a.approve(new PgApproveCommand("pk-1", "order-1", 10_000));

        RoutingPgClient routing = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("A", a, 10)));

        PgCancelResult result = routing.cancel(
                new PgCancelCommand("pk-1", 10_000, "고객 요청", 1, null));

        assertThat(result.transactionKey()).isEqualTo("cancel-tx-A");
    }

    @Test
    @DisplayName("조회도 승인한 PG로 간다")
    void 조회도_그_PG로_간다() {
        ScopedPg a = new ScopedPg("A");
        ScopedPg b = new ScopedPg("B");
        b.approve(new PgApproveCommand("pk-1", "order-1", 10_000));

        RoutingPgClient routing = new RoutingPgClient(List.of(
                RoutingPgClient.PgRoute.of("A", a, 10),
                RoutingPgClient.PgRoute.of("B", b, 5)));

        // A 에게 물으면 NOT_FOUND 가 온다. 복구 배치가 그것을 "승인 안 됨"으로 읽고
        // 살아 있는 결제를 실패로 확정한다
        PgQueryResult result = routing.query("pk-1", "B");

        assertThat(result.isApproved()).isTrue();
    }
}
