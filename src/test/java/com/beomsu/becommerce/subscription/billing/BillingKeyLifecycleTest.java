package com.beomsu.becommerce.subscription.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 빌링키는 <b>카드번호의 대체물</b>이다. 카드가 죽거나 고객이 해지하면 그 대체물도 같이 죽어야 한다.
 *
 * <p>여기서 고정하는 것은 <b>폐기가 사유를 잃지 않는가</b>이다. 카드가 죽어 폐기된 키를 나중에
 * 해지로 다시 폐기하면 진짜 이유가 지워진다 — 대응이 다른 두 가지라 구별이 남아야 한다.
 */
@DisplayName("빌링키 수명주기")
class BillingKeyLifecycleTest {

    private BillingKey key() {
        return BillingKey.of("bk-1", "idx-1", "cust-1", 1L);
    }

    @Test
    @DisplayName("발급 직후에는 쓸 수 있다")
    void activeOnIssue() {
        BillingKey k = key();

        assertThat(k.isActive()).isTrue();
        assertThat(k.getStatus()).isEqualTo(BillingKeyStatus.ACTIVE);
        assertThat(k.getRevokedAt()).isNull();
    }

    @Test
    @DisplayName("폐기하면 사유와 시각이 함께 남는다 — 지우지 않는 이유다")
    void revokeKeepsReasonAndTime() {
        BillingKey k = key();

        k.revoke("카드 거절(HARD_DECLINE)");

        assertThat(k.isActive()).isFalse();
        assertThat(k.getRevokeReason()).isEqualTo("카드 거절(HARD_DECLINE)");
        assertThat(k.getRevokedAt()).isNotNull();
    }

    @Test
    @DisplayName("두 번째 폐기가 첫 사유를 덮어쓰지 않는다 — 진짜 이유가 지워지면 안 된다")
    void secondRevokeDoesNotOverwriteReason() {
        BillingKey k = key();
        k.revoke("카드 거절(HARD_DECLINE)");
        var firstRevokedAt = k.getRevokedAt();

        k.revoke("구독 해지");

        assertThat(k.getRevokeReason()).isEqualTo("카드 거절(HARD_DECLINE)");
        assertThat(k.getRevokedAt()).isEqualTo(firstRevokedAt);
    }
}
