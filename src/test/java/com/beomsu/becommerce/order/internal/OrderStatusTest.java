package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.OrderStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderStatusTest {

    @Test
    @DisplayName("허용된 전이만 참을 반환한다")
    void allowedTransitions() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PAYMENT_IN_PROGRESS)).isTrue();
        assertThat(OrderStatus.PAYMENT_IN_PROGRESS.canTransitionTo(OrderStatus.PAID)).isTrue();
        assertThat(OrderStatus.PAYMENT_IN_PROGRESS.canTransitionTo(OrderStatus.PENDING_PAYMENT)).isTrue(); // 승인 실패 복귀
        assertThat(OrderStatus.PAYMENT_IN_PROGRESS.canTransitionTo(OrderStatus.FAILED)).isTrue();
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.EXPIRED)).isTrue();
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.CANCELED)).isTrue();
    }

    @Test
    @DisplayName("불법 전이는 거짓을 반환한다")
    void illegalTransitions() {
        assertThat(OrderStatus.PENDING_PAYMENT.canTransitionTo(OrderStatus.PAID)).isFalse();   // 승인 단계 건너뛰기 불가
        assertThat(OrderStatus.PAID.canTransitionTo(OrderStatus.PAYMENT_IN_PROGRESS)).isFalse();
        assertThat(OrderStatus.CANCELED.canTransitionTo(OrderStatus.PAID)).isFalse();
        assertThat(OrderStatus.EXPIRED.canTransitionTo(OrderStatus.PENDING_PAYMENT)).isFalse();
    }

    @Test
    @DisplayName("종료 상태는 어디로도 전이할 수 없다")
    void terminalStates() {
        assertThat(OrderStatus.CANCELED.isTerminal()).isTrue();
        assertThat(OrderStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(OrderStatus.FAILED.isTerminal()).isTrue();
        assertThat(OrderStatus.PAID.isTerminal()).isFalse();            // 취소로 전이 가능
        assertThat(OrderStatus.PENDING_PAYMENT.isTerminal()).isFalse();
    }
}
