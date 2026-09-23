package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.settlement.internal.SettlementAdjustmentStatus;
import com.beomsu.becommerce.settlement.internal.SettlementAdjustment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 이미 정산된 뒤에 온 취소를 <b>실행할 일</b>로 남긴다.
 *
 * <p>카운터만 올리면 "몇 건 있었다"는 알지만 어떤 주문을 얼마 조정할지는 복구할 수 없다.
 */
class SettlementAdjustmentTest {

    @Test
    @DisplayName("회수액은 음수로 저장한다 — 차기 정산 총액에 그대로 더하면 된다")
    void clawbackIsStoredAsNegative() {
        var a = SettlementAdjustment.pendingClawback("ORD-1", 10L, 100L, 0, 3_000);

        assertThat(a.getAdjustmentAmount()).isEqualTo(-3_000);
        assertThat(a.getStatus()).isEqualTo(SettlementAdjustmentStatus.PENDING);
        assertThat(a.getOriginalSettlementId())
                .as("어느 지급에서 잘못 나갔는지가 남아야 회수를 추적할 수 있다")
                .isEqualTo(100L);
    }

    @Test
    @DisplayName("회수액이 0 이하면 만들지 않는다 — 회수할 게 없다")
    void rejectsNonPositiveRecoverable() {
        assertThatThrownBy(() -> SettlementAdjustment.pendingClawback("ORD-1", 10L, 100L, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("반영은 멱등이다 — 두 번 적용해도 상태와 대상이 바뀌지 않는다")
    void applyIsIdempotent() {
        var a = SettlementAdjustment.pendingClawback("ORD-1", 10L, 100L, 0, 3_000);

        a.applyTo(200L, null);
        a.applyTo(999L, null);

        assertThat(a.getStatus()).isEqualTo(SettlementAdjustmentStatus.APPLIED);
        assertThat(a.getAppliedSettlementId()).isEqualTo(200L);
    }

    @Test
    @DisplayName("반영된 뒤에는 검토 대상으로 되돌리지 않는다")
    void appliedIsNotDowngradedToReview() {
        var a = SettlementAdjustment.pendingClawback("ORD-1", 10L, 100L, 0, 3_000);
        a.applyTo(200L, null);

        a.requireReview();

        assertThat(a.getStatus()).isEqualTo(SettlementAdjustmentStatus.APPLIED);
    }

    @Test
    @DisplayName("총액을 넘는 회수는 자동 반영하지 않고 사람에게 넘긴다 — 음수 지급은 별도 절차다")
    void oversizedClawbackGoesToReview() {
        var a = SettlementAdjustment.pendingClawback("ORD-1", 10L, 100L, 0, 3_000);

        a.requireReview();

        assertThat(a.getStatus()).isEqualTo(SettlementAdjustmentStatus.REVIEW_REQUIRED);
        assertThat(a.getAppliedSettlementId())
                .as("반영되지 않았으므로 대상 정산도 없어야 한다")
                .isNull();
    }
}
