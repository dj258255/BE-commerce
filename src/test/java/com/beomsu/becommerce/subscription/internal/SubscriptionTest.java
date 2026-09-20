package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.subscription.internal.SubscriptionStatus;
import com.beomsu.becommerce.subscription.internal.SubscriptionException;
import com.beomsu.becommerce.subscription.internal.Subscription;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SubscriptionTest {

    private static Subscription activeSubscription() {
        return Subscription.create(1L, "billing-1", 10_000,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1));
    }

    @Test
    @DisplayName("create: ACTIVE로 생성된다")
    void createsActive() {
        Subscription sub = activeSubscription();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getPlanAmount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("renew: ACTIVE 유지하며 주기를 앞으로 옮긴다")
    void renewAdvancesPeriod() {
        Subscription sub = activeSubscription();

        sub.renew(LocalDate.of(2026, 3, 1));

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(sub.getCurrentPeriodStart()).isEqualTo(LocalDate.of(2026, 2, 1)); // 직전 청구일로 이동
        assertThat(sub.getNextBillingDate()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("enterGrace → hold → recover 전이가 순서대로 성립한다")
    void graceHoldRecoverFlow() {
        Subscription sub = activeSubscription();

        sub.enterGrace();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.IN_GRACE_PERIOD);

        sub.hold();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);

        sub.recover();
        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("hard decline: ACTIVE에서 곧장 hold 할 수 있다")
    void holdDirectlyFromActive() {
        Subscription sub = activeSubscription();

        sub.hold();

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ON_HOLD);
    }

    @Test
    @DisplayName("recover: IN_GRACE_PERIOD에서 ACTIVE로 복귀한다")
    void recoverFromGrace() {
        Subscription sub = activeSubscription();
        sub.enterGrace();

        sub.recover();

        assertThat(sub.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
    }

    @Test
    @DisplayName("불법 전이는 예외를 던진다 (해지된 구독은 유예 진입 불가)")
    void illegalTransitionThrows() {
        Subscription sub = activeSubscription();
        sub.cancel();

        assertThatThrownBy(sub::enterGrace)
                .isInstanceOf(SubscriptionException.class)
                .satisfies(e -> assertThat(((SubscriptionException) e).code())
                        .isEqualTo("INVALID_SUBSCRIPTION_TRANSITION"));
    }

    @Test
    @DisplayName("renew: ACTIVE가 아니면 예외 (유예/정지 구독은 먼저 복귀해야 함)")
    void renewRequiresActive() {
        Subscription sub = activeSubscription();
        sub.enterGrace();

        assertThatThrownBy(() -> sub.renew(LocalDate.of(2026, 3, 1)))
                .isInstanceOf(SubscriptionException.class)
                .satisfies(e -> assertThat(((SubscriptionException) e).code())
                        .isEqualTo("SUBSCRIPTION_NOT_ACTIVE"));
    }
}
