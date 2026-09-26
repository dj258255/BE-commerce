package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 주문 생성 때 여유만 확인하는 전략(CHECK, #374). ADR-003 이 적은 흐름이다. 잡지 않으므로 사이에 팔릴 수 있다. */
@Tag("integration")
@SpringBootTest(properties = "app.stock.reservation=CHECK")
@DisplayName("재고 CHECK — 여유가 없으면 주문을 거절하고, 있으면 예약 없이 승인 뒤에 뺀다")
class StockReservationCheckIntegrationTest extends StockReservationTestSupport {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "StockResCheck");
    }

    @Test
    @DisplayName("재고 0 이면 주문을 만들지 않는다")
    void soldOutRejectsOrder() {
        long p = product(0);
        assertThatThrownBy(() -> order(user(), p))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("OUT_OF_STOCK"));
    }

    @Test
    @DisplayName("여유가 있으면 잡지 않고 주문을 만들며, 승인 뒤에 뺀다")
    void availableDoesNotReserve() {
        long p = product(1);
        long u = user();
        String o = order(u, p);
        assertThat(stock(p)).isEqualTo(1);
        assertThat(reservations(o)).isEmpty();

        assertThat(pay(o, u, "pk-" + UUID.randomUUID()).orderStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(stock(p)).isZero();
    }

    @Test
    @DisplayName("둘이 여유를 확인하고 둘 다 결제하면 뒤의 하나는 승인 뒤 망취소된다(잡지 않는 대가)")
    void checkedTwiceSecondIsNetCancelled() {
        long p = product(1);
        long u1 = user();
        long u2 = user();
        String first = order(u1, p);
        String second = order(u2, p);   // 아직 재고 1 이라 통과한다

        pay(first, u1, "pk-" + UUID.randomUUID());
        CheckoutResult r = pay(second, u2, "pk-" + UUID.randomUUID());

        assertThat(r.orderStatus()).isEqualTo(OrderStatus.FAILED);
        assertThat(netCancels(second)).isEqualTo(1);
        assertThat(stock(p)).isZero();
    }
}
