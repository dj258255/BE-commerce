package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.settlement.internal.SettlementStatus;
import com.beomsu.becommerce.settlement.internal.Settlement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SettlementTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 5);
    private static final LocalDate PAYOUT = LocalDate.of(2026, 7, 7);
    private static final long PLATFORM = com.beomsu.becommerce.seller.SellerPayoutGate.PLATFORM_SELLER_ID;

    @Test
    @DisplayName("불변식: netAmount = grossAmount - feeAmount - feeVatAmount")
    void netEqualsGrossMinusFeeAndVat() {
        Settlement settlement = Settlement.of(DATE, "KRW", 100_000, 2_700, 270, 2, PAYOUT, PLATFORM);

        assertThat(settlement.getNetAmount()).isEqualTo(97_030);
        assertThat(settlement.getNetAmount())
                .isEqualTo(settlement.getGrossAmount() - settlement.getFeeAmount() - settlement.getFeeVatAmount());
        assertThat(settlement.getFeeVatAmount()).isEqualTo(270);
        assertThat(settlement.getPayoutDate()).isEqualTo(PAYOUT);
        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.CREATED);
        assertThat(settlement.getPaidOutAt()).isNull();
    }

    @Test
    @DisplayName("수수료+부가세가 총액보다 크면 정산을 만들 수 없다")
    void feePlusVatCannotExceedGross() {
        assertThatThrownBy(() -> Settlement.of(DATE, "KRW", 1_000, 900, 200, 1, PAYOUT, PLATFORM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("음수 금액으로는 정산을 만들 수 없다")
    void negativeAmountRejected() {
        assertThatThrownBy(() -> Settlement.of(DATE, "KRW", -1, 0, 0, 0, PAYOUT, PLATFORM))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markPaidOut: CREATED → PAID_OUT 전이하고 paidOutAt 세팅")
    void markPaidOutTransitions() {
        Settlement settlement = Settlement.of(DATE, "KRW", 100_000, 2_700, 270, 2, PAYOUT, PLATFORM);
        settlement.recordPayoutReconciliation(
                new PayoutReconciliationEngine.Result(PayoutReconciliationStatus.MATCHED,
                        settlement.getPayoutInstructionReference(), "KRW", settlement.getNetAmount(),
                        settlement.getNetAmount(), 0, "match"),
                settlement.getPayoutInstructionReference());

        settlement.markPaidOut();

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(settlement.getPaidOutAt()).isNotNull();
    }

    @Test
    @DisplayName("markPaidOut 멱등: 이미 PAID_OUT이면 상태·시각을 덮어쓰지 않는다")
    void markPaidOutIsIdempotent() {
        Settlement settlement = Settlement.of(DATE, "KRW", 100_000, 2_700, 270, 2, PAYOUT, PLATFORM);
        settlement.recordPayoutReconciliation(
                new PayoutReconciliationEngine.Result(PayoutReconciliationStatus.MATCHED,
                        settlement.getPayoutInstructionReference(), "KRW", settlement.getNetAmount(),
                        settlement.getNetAmount(), 0, "match"),
                settlement.getPayoutInstructionReference());
        settlement.markPaidOut();
        Instant firstPaidOutAt = settlement.getPaidOutAt();

        settlement.markPaidOut(); // 두 번째 호출

        assertThat(settlement.getStatus()).isEqualTo(SettlementStatus.PAID_OUT);
        assertThat(settlement.getPaidOutAt()).isEqualTo(firstPaidOutAt); // 미변경
    }

    @Test
    @DisplayName("대사가 MATCHED가 아니면 지급 확정할 수 없다")
    void payoutRequiresMatchedReconciliation() {
        Settlement settlement = Settlement.of(DATE, "KRW", 100_000, 2_700, 270, 2, PAYOUT, PLATFORM);

        assertThatThrownBy(settlement::markPaidOut)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MATCHED");
    }
}
