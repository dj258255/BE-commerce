package com.beomsu.becommerce.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentStatusTest {

    @Test
    @DisplayName("허용된 전이만 참을 반환한다")
    void allowedTransitions() {
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.IN_PROGRESS)).isTrue();
        assertThat(PaymentStatus.IN_PROGRESS.canTransitionTo(PaymentStatus.DONE)).isTrue();
        assertThat(PaymentStatus.IN_PROGRESS.canTransitionTo(PaymentStatus.UNKNOWN)).isTrue();
        assertThat(PaymentStatus.DONE.canTransitionTo(PaymentStatus.PARTIAL_CANCELED)).isTrue();
        assertThat(PaymentStatus.UNKNOWN.canTransitionTo(PaymentStatus.CANCELED)).isTrue(); // 망취소
    }

    @Test
    @DisplayName("불법 전이는 거짓을 반환한다")
    void illegalTransitions() {
        assertThat(PaymentStatus.CANCELED.canTransitionTo(PaymentStatus.DONE)).isFalse();
        assertThat(PaymentStatus.READY.canTransitionTo(PaymentStatus.DONE)).isFalse(); // 인증 건너뛰기 불가
        assertThat(PaymentStatus.DONE.canTransitionTo(PaymentStatus.UNKNOWN)).isFalse();
    }

    @Test
    @DisplayName("종료 상태는 어디로도 전이할 수 없다")
    void terminalStates() {
        assertThat(PaymentStatus.CANCELED.isTerminal()).isTrue();
        assertThat(PaymentStatus.ABORTED.isTerminal()).isTrue();
        assertThat(PaymentStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(PaymentStatus.DONE.isTerminal()).isFalse(); // 취소로 전이 가능
    }
}
