package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTest {

    @Test
    @DisplayName("재고가 충분하면 차감된다")
    void deductSuccess() {
        Stock stock = Stock.of(100L, 10);

        stock.deduct(3);

        assertThat(stock.getQuantity()).isEqualTo(7);
    }

    @Test
    @DisplayName("정확히 재고 수량만큼 차감하면 0이 된다")
    void deductToZero() {
        Stock stock = Stock.of(100L, 5);

        stock.deduct(5);

        assertThat(stock.getQuantity()).isZero();
    }

    @Test
    @DisplayName("재고보다 많이 차감하면 OUT_OF_STOCK 예외 (수량 불변)")
    void deductInsufficient() {
        Stock stock = Stock.of(100L, 2);

        assertThatThrownBy(() -> stock.deduct(3))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("OUT_OF_STOCK"));
        assertThat(stock.getQuantity()).isEqualTo(2); // 실패 시 차감되지 않는다
    }

    @Test
    @DisplayName("음수/0 차감은 거부한다 (음수 차감이 재고를 늘리는 것을 방지)")
    void rejectsNonPositiveDeduct() {
        Stock stock = Stock.of(100L, 10);

        assertThatThrownBy(() -> stock.deduct(-5))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_REQUEST"));
        assertThat(stock.getQuantity()).isEqualTo(10); // 늘어나지 않는다
    }
}
