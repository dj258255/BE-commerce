package com.beomsu.becommerce.reconciliation.cause;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResult;
import com.beomsu.becommerce.payment.PaymentTimelineFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 불일치 원인 규칙 분류기 (ADR-012).
 *
 * <p>여기서 지키려는 것은 <b>규칙 간 순서</b>다. 설명이 되는 원인을 찾았으면
 * "설명되지 않는다"를 전제로 만든 후보는 내지 않아야 한다.
 * 실측에서 이걸 놓쳐 수수료가 정확히 맞는 건에 위변조 의심이 함께 붙었고,
 * 그러면 사람이 매번 배제 확인을 해야 해서 <b>제안이 오히려 일을 늘린다.</b>
 */
class CauseClassifierTest {

    private static final long FEE_BPS = 270;   // 2.7%
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 8, 30);

    private final PaymentTimelineFacts paymentFacts = mock(PaymentTimelineFacts.class);
    private final ReconciliationResultRepository repository = mock(ReconciliationResultRepository.class);
    private final CauseClassifier classifier = new CauseClassifier(paymentFacts, repository, FEE_BPS);

    private static ReconciliationResult mismatch(long internal, long external) {
        return ReconciliationResult.amountMismatch(TRADE_DATE, "ord-1", internal, external);
    }

    private void paymentWithCancel(long amount, long balance, int cancelCount) {
        when(paymentFacts.findState(anyString())).thenReturn(Optional.of(
                new PaymentTimelineFacts.PaymentState(amount, balance, cancelCount, "DONE", Instant.now())));
    }

    @Test
    @DisplayName("차액이 수수료율과 정확히 맞으면 DECISIVE — 그리고 그것만 낸다")
    void feeDifferenceIsDecisiveAndExclusive() {
        paymentWithCancel(10_000, 10_000, 0);   // 취소 없음

        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 9_730));   // 차액 270 = 2.7%

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.cause()).isEqualTo(ResolveCause.FEE_CALCULATION_DIFF);
            assertThat(s.confidence()).isEqualTo(CauseSuggestion.Confidence.DECISIVE);
            assertThat(s.evidence()).contains("270");
        });
        // 설명이 됐으므로 위변조 의심을 함께 내지 않는다 — 내면 사람이 매번 배제해야 한다
        assertThat(out).extracting(CauseSuggestion::cause)
                .doesNotContain(ResolveCause.SUSPECTED_TAMPERING);
    }

    @Test
    @DisplayName("차액이 취소 금액과 정확히 맞으면 부분취소 미반영 — 취소는 balance로 계산된다")
    void canceledAmountMatchingDiff() {
        paymentWithCancel(10_000, 7_000, 1);    // 3,000원 취소됨

        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 7_000));   // 차액 3,000

        assertThat(out).extracting(CauseSuggestion::cause)
                .containsExactly(ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED);
    }

    @Test
    @DisplayName("수수료로도 취소로도 설명 안 되면 그때만 위변조 의심 — 근거에 배제 사유를 적는다")
    void unexplainedDifferenceRaisesSuspicion() {
        paymentWithCancel(10_000, 10_000, 0);

        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 5_000));   // 차액 5,000

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.cause()).isEqualTo(ResolveCause.SUSPECTED_TAMPERING);
            assertThat(s.confidence()).isEqualTo(CauseSuggestion.Confidence.WEAK);
            // 무엇으로 설명이 안 되는지를 근거에 남긴다 — 사람이 검증할 수 있어야 한다
            assertThat(s.evidence()).contains("수수료").contains("취소 이력이 없다");
        });
    }

    @Test
    @DisplayName("취소가 있지만 금액이 다르면 그 사실을 근거에 적는다 — 배제도 정보다")
    void cancelExistsButAmountDiffers() {
        paymentWithCancel(10_000, 8_000, 1);    // 2,000 취소인데 차액은 5,000

        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 5_000));

        assertThat(out).singleElement().satisfies(s ->
                assertThat(s.evidence()).contains("취소 2,000원이 있지만 차액과 다르다"));
    }

    @Test
    @DisplayName("외부에만 있으면 내부 기록 유실을 제안한다")
    void externalOnly() {
        List<CauseSuggestion> out = classifier.suggest(
                ReconciliationResult.externalOnly(TRADE_DATE, "ord-ghost", 7_000));

        assertThat(out).extracting(CauseSuggestion::cause)
                .containsExactly(ResolveCause.INTERNAL_RECORD_LOST);
    }

    @Test
    @DisplayName("결제 기록조차 못 찾으면 그 사실을 근거에 남긴다 — 조용히 넘어가지 않는다")
    void missingPaymentIsStated() {
        when(paymentFacts.findState(anyString())).thenReturn(Optional.empty());

        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 5_000));

        assertThat(out).singleElement().satisfies(s ->
                assertThat(s.evidence()).contains("결제 기록을 찾지 못했다"));
    }

    @Test
    @DisplayName("외부가 내부의 정수배면 중복 기록 — 위변조로 몰지 않는다")
    void externalIsMultipleOfInternalMeansDuplicate() {
        paymentWithCancel(10_000, 10_000, 0);

        // PG 파일에 같은 건이 두 번 실리면 외부가 내부의 2배로 온다.
        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 20_000));

        assertThat(out).singleElement().satisfies(s -> {
            assertThat(s.cause()).isEqualTo(ResolveCause.DUPLICATE_RECORD);
            // 결정적이 아니다 — 실제로 두 번 결제되고 내부에 1건만 남은 경우도 같은 숫자가 된다.
            assertThat(s.confidence()).isEqualTo(CauseSuggestion.Confidence.LIKELY);
            assertThat(s.evidence()).contains("2배").contains("2번 실렸을 수 있다");
        });
    }

    @Test
    @DisplayName("중복이면 위변조 의심을 함께 내지 않는다 — 설명이 있는데 의심을 붙이면 배제 확인이 늘어난다")
    void duplicateSuppressesSuspicion() {
        paymentWithCancel(10_000, 10_000, 0);

        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 30_000));   // 3배

        assertThat(out).extracting(CauseSuggestion::cause)
                .containsExactly(ResolveCause.DUPLICATE_RECORD)
                .doesNotContain(ResolveCause.SUSPECTED_TAMPERING);
    }

    @Test
    @DisplayName("배수가 아니면 중복이 아니다 — 그때는 종전대로 의심이 남는다")
    void nonMultipleIsStillSuspicious() {
        paymentWithCancel(10_000, 10_000, 0);

        // 외부가 더 크지만 정수배가 아니다.
        List<CauseSuggestion> out = classifier.suggest(mismatch(10_000, 17_000));

        assertThat(out).extracting(CauseSuggestion::cause)
                .containsExactly(ResolveCause.SUSPECTED_TAMPERING);
    }
}
