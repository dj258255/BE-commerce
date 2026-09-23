package com.beomsu.becommerce.payment.va;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VaStatusTest {

    @Test
    @DisplayName("허용된 전이만 참을 반환한다")
    void allowedTransitions() {
        assertThat(VaStatus.WAITING_FOR_DEPOSIT.canTransitionTo(VaStatus.DONE)).isTrue();
        assertThat(VaStatus.WAITING_FOR_DEPOSIT.canTransitionTo(VaStatus.EXPIRED)).isTrue();
        assertThat(VaStatus.WAITING_FOR_DEPOSIT.canTransitionTo(VaStatus.CANCELED)).isTrue();
        assertThat(VaStatus.DONE.canTransitionTo(VaStatus.CANCELED)).isTrue();
    }

    @Test
    @DisplayName("DONE → WAITING_FOR_DEPOSIT 역전이를 허용한다(은행 지연 통보)")
    void doneToWaitingReversalAllowed() {
        assertThat(VaStatus.DONE.canTransitionTo(VaStatus.WAITING_FOR_DEPOSIT)).isTrue();
    }

    @Test
    @DisplayName("불법 전이는 거짓을 반환한다")
    void illegalTransitions() {
        assertThat(VaStatus.WAITING_FOR_DEPOSIT.canTransitionTo(VaStatus.WAITING_FOR_DEPOSIT)).isFalse();
        assertThat(VaStatus.DONE.canTransitionTo(VaStatus.EXPIRED)).isFalse(); // 입금된 건은 만료 불가
        assertThat(VaStatus.EXPIRED.canTransitionTo(VaStatus.DONE)).isFalse();
        assertThat(VaStatus.CANCELED.canTransitionTo(VaStatus.DONE)).isFalse();
    }

    @Test
    @DisplayName("종료 상태는 어디로도 전이할 수 없다")
    void terminalStates() {
        assertThat(VaStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(VaStatus.CANCELED.isTerminal()).isTrue();
        assertThat(VaStatus.WAITING_FOR_DEPOSIT.isTerminal()).isFalse();
        assertThat(VaStatus.DONE.isTerminal()).isFalse(); // 역전이·취소로 전이 가능
    }
}
