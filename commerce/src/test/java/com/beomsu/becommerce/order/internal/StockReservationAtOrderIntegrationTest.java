package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.recovery.OrderExpiryService;
import com.beomsu.becommerce.payment.pg.PgApproveResult;
import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 주문 생성에 재고를 잡는 전략(AT_ORDER, #374). ADR-003 이 버린 안이라 이탈 주문이 만료까지 문다. */
@Tag("integration")
@SpringBootTest(properties = "app.stock.reservation=AT_ORDER")
@DisplayName("재고 예약 AT_ORDER — 주문 생성에 잡고, 거절이면 유지, 만료면 되돌린다")
class StockReservationAtOrderIntegrationTest extends StockReservationTestSupport {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "StockResAtOrder");
    }

    @Autowired
    OrderExpiryService orderExpiryService;

    @Test
    @DisplayName("주문 생성에 재고가 빠지고, 결제 승인은 확정만 한다")
    void orderReservesPaymentClaims() {
        long p = product(2);
        long u = user();
        String o = order(u, p);
        assertThat(stock(p)).isEqualTo(1);
        assertThat(reservations(o)).containsExactly("RESERVED");

        assertThat(pay(o, u, "pk-" + UUID.randomUUID()).orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stock(p)).isEqualTo(1);   // 두 번 빠지지 않는다
        assertThat(reservations(o)).containsExactly("CLAIMED");
    }

    @Test
    @DisplayName("결제 거절이면 예약을 유지한다(같은 주문으로 다시 결제할 수 있다)")
    void declineKeepsReservation() {
        long p = product(1);
        long u = user();
        String o = order(u, p);
        fakePg.setNextResult(PgApproveResult.failed("카드 거절"));

        pay(o, u, "pk-" + UUID.randomUUID());

        assertThat(orderStatus(o)).isEqualTo("PENDING_PAYMENT");
        assertThat(reservations(o)).containsExactly("RESERVED");
        assertThat(stock(p)).isZero();
    }

    @Test
    @DisplayName("매진이면 주문 자체를 만들지 않는다")
    void soldOutRejectsOrder() {
        long p = product(1);
        order(user(), p);

        assertThatThrownBy(() -> order(user(), p))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("OUT_OF_STOCK"));
        assertThat(stock(p)).isZero();
    }

    @Test
    @DisplayName("결제하지 않은 주문이 만료되면 잡은 재고를 되돌린다")
    void expiryReleases() {
        long p = product(1);
        String o = order(user(), p);
        assertThat(stock(p)).isZero();
        jdbc.update("UPDATE orders SET expires_at = NOW(6) - INTERVAL 1 MINUTE WHERE order_no = ?", o);

        orderExpiryService.expireOverdue(Instant.now());

        assertThat(orderStatus(o)).isEqualTo("EXPIRED");
        assertThat(reservations(o)).containsExactly("RELEASED");
        assertThat(stock(p)).isEqualTo(1);
    }
}
