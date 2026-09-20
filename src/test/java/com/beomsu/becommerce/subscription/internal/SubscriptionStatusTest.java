package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.subscription.internal.SubscriptionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SubscriptionStatusTest {

    @Test
    @DisplayName("허용된 전이만 참을 반환한다")
    void allowedTransitions() {
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.IN_GRACE_PERIOD)).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.CANCELED)).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.ON_HOLD)).isTrue(); // hard decline 직행
        assertThat(SubscriptionStatus.IN_GRACE_PERIOD.canTransitionTo(SubscriptionStatus.ACTIVE)).isTrue(); // 결제 성공 복귀
        assertThat(SubscriptionStatus.IN_GRACE_PERIOD.canTransitionTo(SubscriptionStatus.ON_HOLD)).isTrue();
        assertThat(SubscriptionStatus.ON_HOLD.canTransitionTo(SubscriptionStatus.ACTIVE)).isTrue();
        assertThat(SubscriptionStatus.ON_HOLD.canTransitionTo(SubscriptionStatus.EXPIRED)).isTrue();
    }

    @Test
    @DisplayName("불법 전이는 거짓을 반환한다")
    void illegalTransitions() {
        assertThat(SubscriptionStatus.ACTIVE.canTransitionTo(SubscriptionStatus.EXPIRED)).isFalse();
        assertThat(SubscriptionStatus.ON_HOLD.canTransitionTo(SubscriptionStatus.IN_GRACE_PERIOD)).isFalse();
        assertThat(SubscriptionStatus.IN_GRACE_PERIOD.canTransitionTo(SubscriptionStatus.CANCELED)).isFalse();
        assertThat(SubscriptionStatus.CANCELED.canTransitionTo(SubscriptionStatus.ACTIVE)).isFalse();
    }

    @Test
    @DisplayName("종료 상태는 어디로도 전이할 수 없다")
    void terminalStates() {
        assertThat(SubscriptionStatus.CANCELED.isTerminal()).isTrue();
        assertThat(SubscriptionStatus.EXPIRED.isTerminal()).isTrue();
        assertThat(SubscriptionStatus.ACTIVE.isTerminal()).isFalse();
        assertThat(SubscriptionStatus.IN_GRACE_PERIOD.isTerminal()).isFalse();
        assertThat(SubscriptionStatus.ON_HOLD.isTerminal()).isFalse();
    }
}
