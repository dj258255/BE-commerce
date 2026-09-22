package com.beomsu.becommerce.settlement;

import com.beomsu.becommerce.settlement.internal.PayoutReconciliationEngine;
import com.beomsu.becommerce.settlement.internal.PayoutReconciliationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PayoutReconciliationEngineTest {

    private final PayoutReconciliationEngine engine = new PayoutReconciliationEngine();

    @Test
    @DisplayName("posted 지급은 reference·통화·금액이 모두 같을 때만 MATCHED가 된다")
    void matchesPostedPayout() {
        var expected = new PayoutReconciliationEngine.SettlementExpectation("payout-1", "KRW", 97_300);

        var result = engine.reconcile(
                List.of(expected),
                List.of(PayoutReconciliationEngine.ExternalPayout.posted("payout-1", "KRW", 97_300)));

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(PayoutReconciliationStatus.MATCHED);
            assertThat(r.difference()).isZero();
        });
    }

    @Test
    @DisplayName("금액이 맞아도 pending 외부 행은 지급 확정으로 올리지 않는다")
    void pendingNeverBecomesMatched() {
        var result = engine.reconcile(
                List.of(new PayoutReconciliationEngine.SettlementExpectation("payout-1", "KRW", 97_300)),
                List.of(PayoutReconciliationEngine.ExternalPayout.pending("payout-1", "KRW", 97_300)));

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(PayoutReconciliationStatus.PENDING);
            assertThat(r.actualAmount()).isEqualTo(97_300L);
        });
    }

    @Test
    @DisplayName("posted 금액 차이는 방향을 보존한 AMOUNT_MISMATCH로 남긴다")
    void reportsAmountDifference() {
        var result = engine.reconcile(
                List.of(new PayoutReconciliationEngine.SettlementExpectation("payout-1", "KRW", 97_300)),
                List.of(PayoutReconciliationEngine.ExternalPayout.posted("payout-1", "KRW", 96_800)));

        assertThat(result).singleElement().satisfies(r -> {
            assertThat(r.status()).isEqualTo(PayoutReconciliationStatus.AMOUNT_MISMATCH);
            assertThat(r.difference()).isEqualTo(-500L);
        });
    }

    @Test
    @DisplayName("reference가 없거나 중복된 외부 지급은 금액만으로 내부 정산을 닫지 않는다")
    void doesNotCloseWithoutUniqueReference() {
        var result = engine.reconcile(
                List.of(new PayoutReconciliationEngine.SettlementExpectation("payout-1", "KRW", 97_300)),
                List.of(
                        PayoutReconciliationEngine.ExternalPayout.posted(null, "KRW", 97_300),
                        PayoutReconciliationEngine.ExternalPayout.posted("payout-1", "KRW", 97_300),
                        PayoutReconciliationEngine.ExternalPayout.posted("payout-1", "KRW", 97_300)));

        assertThat(result).extracting(PayoutReconciliationEngine.Result::status)
                .containsExactlyInAnyOrder(
                        PayoutReconciliationStatus.DUPLICATE_PAYOUT_REFERENCE,
                        PayoutReconciliationStatus.UNMATCHED_PAYOUT);
    }

    @Test
    @DisplayName("내부에만 있거나 외부에만 있는 지급은 각각 다른 예외로 구분한다")
    void distinguishesMissingSides() {
        var result = engine.reconcile(
                List.of(new PayoutReconciliationEngine.SettlementExpectation("expected-only", "KRW", 100),
                        new PayoutReconciliationEngine.SettlementExpectation("external-only", "KRW", 200)),
                List.of(PayoutReconciliationEngine.ExternalPayout.posted("external-only", "KRW", 200)));

        assertThat(result).extracting(PayoutReconciliationEngine.Result::status)
                .containsExactly(
                        PayoutReconciliationStatus.UNMATCHED_SETTLEMENT,
                        PayoutReconciliationStatus.MATCHED);
    }

    @Test
    @DisplayName("통화가 다르면 환율을 추측하지 않고 CURRENCY_MISMATCH로 남긴다")
    void doesNotGuessExchangeRate() {
        var result = engine.reconcile(
                List.of(new PayoutReconciliationEngine.SettlementExpectation("payout-1", "KRW", 100)),
                List.of(PayoutReconciliationEngine.ExternalPayout.posted("payout-1", "USD", 1)));

        assertThat(result).singleElement().extracting(PayoutReconciliationEngine.Result::status)
                .isEqualTo(PayoutReconciliationStatus.CURRENCY_MISMATCH);
    }

    @Test
    @DisplayName("내부 지급 reference가 중복되면 결정할 수 없으므로 입력을 거부한다")
    void rejectsDuplicateInternalReference() {
        var expected = new PayoutReconciliationEngine.SettlementExpectation("payout-1", "KRW", 100);

        assertThatThrownBy(() -> engine.reconcile(List.of(expected, expected), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("중복");
    }
}
