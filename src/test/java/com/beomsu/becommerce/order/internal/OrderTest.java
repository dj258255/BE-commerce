package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.OrderException;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    private Order sampleOrder() {
        // 10,000 x 2 + 5,000 x 1 = 25,000
        return Order.create(1L, List.of(
                OrderItem.of(100L, "상품A", 10_000, 2),
                OrderItem.of(200L, "상품B", 5_000, 1)));
    }

    @Test
    @DisplayName("create는 항목 소계의 합으로 totalAmount를 계산하고 PENDING_PAYMENT로 생성한다")
    void createComputesTotal() {
        Order order = sampleOrder();

        assertThat(order.getTotalAmount()).isEqualTo(25_000);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(order.getItems()).hasSize(2);
        assertThat(order.getOrderNo()).isNotBlank();
        assertThat(order.getExpiresAt()).isAfter(order.getCreatedAt());
    }

    @Test
    @DisplayName("항목은 주문 시점 상품명·단가를 스냅샷으로 고정한다")
    void itemSnapshot() {
        Order order = sampleOrder();
        OrderItem first = order.getItems().get(0);

        assertThat(first.getProductName()).isEqualTo("상품A");
        assertThat(first.getUnitPrice()).isEqualTo(10_000);
        assertThat(first.subtotal()).isEqualTo(20_000);
        assertThat(first.getOrder()).isSameAs(order);
    }

    @Test
    @DisplayName("verifyAmount는 금액이 일치하면 통과, 불일치하면 AMOUNT_MISMATCH 예외")
    void verifyAmount() {
        Order order = sampleOrder();

        order.verifyAmount(Money.krw(25_000)); // 통과 (예외 없음)

        assertThatThrownBy(() -> order.verifyAmount(Money.krw(24_000)))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("AMOUNT_MISMATCH"));
    }

    @Test
    @DisplayName("정상 결제 플로우: PENDING_PAYMENT → PAYMENT_IN_PROGRESS → PAID")
    void paymentFlow() {
        Order order = sampleOrder();

        order.startPayment();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_IN_PROGRESS);

        order.markPaid();
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("승인 실패 시 PAYMENT_IN_PROGRESS → PENDING_PAYMENT로 복귀한다")
    void revertToPending() {
        Order order = sampleOrder();
        order.startPayment();

        order.revertToPending();

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    @Test
    @DisplayName("불법 전이 시도는 INVALID_STATE_TRANSITION 예외 (승인 단계 건너뛰기)")
    void illegalTransitionThrows() {
        Order order = sampleOrder();

        assertThatThrownBy(order::markPaid) // PENDING_PAYMENT → PAID 직행 불가
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code())
                        .isEqualTo("INVALID_STATE_TRANSITION"));
    }

    @Test
    @DisplayName("빈 항목으로는 주문을 만들 수 없다")
    void emptyItemsRejected() {
        assertThatThrownBy(() -> Order.create(1L, List.of()))
                .isInstanceOf(OrderException.class);
    }
}
