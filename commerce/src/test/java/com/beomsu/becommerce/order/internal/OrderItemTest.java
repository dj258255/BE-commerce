package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderItemTest {

    @Test
    @DisplayName("정상 소계 계산")
    void subtotal() {
        assertThat(OrderItem.of(1L, "A", 10_000, 3).subtotal()).isEqualTo(30_000);
    }

    @Test
    @DisplayName("수량이 0 이하면 예외 (음수 금액·무료 결제 방지)")
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> OrderItem.of(1L, "A", 10_000, 0))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_REQUEST"));
        assertThatThrownBy(() -> OrderItem.of(1L, "A", 10_000, -5))
                .isInstanceOf(OrderException.class);
    }

    @Test
    @DisplayName("단가가 음수면 예외")
    void rejectsNegativePrice() {
        assertThatThrownBy(() -> OrderItem.of(1L, "A", -1, 1))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_REQUEST"));
    }

    @Test
    @DisplayName("단가 × 수량 오버플로는 조용히 뒤집히지 않고 예외")
    void detectsOverflow() {
        assertThatThrownBy(() -> OrderItem.of(1L, "A", Long.MAX_VALUE, 2).subtotal())
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("AMOUNT_OVERFLOW"));
    }
}
