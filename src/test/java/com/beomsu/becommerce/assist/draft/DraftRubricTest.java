package com.beomsu.becommerce.assist.draft;

import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.ResolveCause;

import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.TimelineEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정답 없이 채점하는 루브릭.
 *
 * <p>항목은 상상해서 만든 게 아니라 <b>블라인드 리뷰에서 실제로 고쳐야 했던 것</b>들이다.
 * 이 테스트가 지키는 건 "그 실패를 정말로 잡는가"이고, 못 잡으면 표본을 늘려도 소용없다.
 */
class DraftRubricTest {

    private final DraftRubric rubric = new DraftRubric(new NumericProvenanceGuard(), new CustomerGlossary());

    private FactPack facts() {
        OrderTimeline t = new OrderTimeline("ORD-1",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "MISMATCH",
                        "대사 AMOUNT_MISMATCH — 내부 10,000 / 외부 1,112", 10_000L, java.util.List.of(10000L, 1112L),
                        java.util.List.<java.time.LocalDate>of())),
                List.of());
        return FactPack.from(t, new CauseSuggestion(ResolveCause.SUSPECTED_TAMPERING,
                CauseSuggestion.Confidence.WEAK,
                "차액 8,888원이 수수료(270원)로도 설명되지 않는다",
                java.util.Set.of(8_888L, 270L)));
    }


    /** 원인이 확정된 건 — 고객 영향을 요구해도 되는 자리. */
    private FactPack decisiveFacts() {
        OrderTimeline t = new OrderTimeline("ORD-D",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "MISMATCH",
                        "대사 AMOUNT_MISMATCH — 내부 10,000 / 외부 1,112", 10_000L, java.util.List.of(10000L, 1112L),
                        java.util.List.<java.time.LocalDate>of())),
                List.of());
        return FactPack.from(t, CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF,
                "차액 8,888원이 수수료(270원)로 확정", 8_888L, 270L));
    }

    @Test
    @DisplayName("좋은 초안은 만점")
    void goodDraftScoresFull() {
        String draft = "2026-08-30 결제 10,000원은 정상 승인되었습니다. "
                + "결제사 정산 내역과의 차액 8,888원의 원인을 확인 중이며, "
                + "확인이 끝날 때까지 추가로 청구되는 금액은 없습니다. 결과가 나오면 안내드리겠습니다.";
        DraftRubric.Score s = rubric.score(draft, facts());
        assertThat(s.failed()).isEmpty();
        assertThat(s.ratio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("실측에서 실제로 나왔던 나쁜 초안을 잡는다 — 문제를 말하지 않고 딴 얘기만 한다")
    void catchesTheRealFailure() {
        // 블라인드 리뷰 당시 위변조 의심 건에서 실제로 나온 초안
        String draft = "2026-08-30 결제 승인 완료 및 주문 상태 PAID 확인 중입니다. "
                + "에스크로 보류 자동 해제 예정일은 2026-09-06입니다.";
        DraftRubric.Score s = rubric.score(draft, decisiveFacts());

        assertThat(s.failed())
                .anySatisfy(f -> assertThat(f).contains("핵심 수치 누락"))
                .anySatisfy(f -> assertThat(f).contains("내부 용어"))
                .anySatisfy(f -> assertThat(f).contains("고객 영향 없음"));
    }

    @Test
    @DisplayName("핵심 수치는 표기가 달라도 인정한다 — 8,888 과 8888")
    void acceptsEitherAmountFormat() {
        String a = "차액 8,888원을 확인 중이며 추가 청구는 없습니다. 확인 후 안내드리겠습니다. 결제는 정상입니다.";
        String b = a.replace("8,888", "8888");
        assertThat(rubric.score(a, facts()).failed()).isEmpty();
        assertThat(rubric.score(b, facts()).failed()).isEmpty();
    }

    @Test
    @DisplayName("근거에 금액이 없으면 핵심 수치를 요구하지 않는다 — 없는 것을 말하라고 할 수 없다")
    void skipsKeyFigureWhenHintHasNone() {
        OrderTimeline t = new OrderTimeline("ORD-2",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "EXT", "외부에만 존재", 50_000L)),
                List.of());
        FactPack f = FactPack.from(t,
                CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF, "INTERNAL_RECORD_LOST (LIKELY) — 외부에만 존재"));

        String draft = "문의하신 결제 건을 저희 기록에서 찾지 못해 확인 중입니다. "
                + "카드사 승인 내역을 보내주시면 확인이 빨라집니다. 확인되면 처리해 드리겠습니다.";
        assertThat(rubric.score(draft, f).failed()).isEmpty();
    }

    @Test
    @DisplayName("초안이 없으면 0점 — 통계에서 조용히 빠지지 않게 한다")
    void missingDraftScoresZero() {
        assertThat(rubric.score(null, facts()).passed()).isZero();
        assertThat(rubric.score("  ", facts()).failed()).containsExactly("초안 없음");
    }

    @Test
    @DisplayName("너무 짧으면 잡는다")
    void catchesTooShort() {
        assertThat(rubric.score("확인 중입니다.", facts()).failed())
                .anySatisfy(f -> assertThat(f).contains("너무 짧음"));
    }

    @Test
    @DisplayName("마무리 인사만으로는 고객 영향을 통과시키지 않는다 — 실측 6/6이 이걸로 통과했었다")
    void closingGreetingAloneIsNotImpact() {
        String draft = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "결제사 정산 내역의 금액이 달라 차액 8,888원의 원인을 확인 중입니다. "
                + "취소 이력은 없으며, 확인이 끝나는 대로 안내드리겠습니다.";
        // 원인이 확정된 건이어야 영향을 요구한다 — 모르는 건에 요구하면 지어낸다
        DraftRubric.Score s = rubric.score(draft, decisiveFacts());

        assertThat(s.failed())
                .as("이 문장은 고객에게 돈이 어떻게 되는지를 알려주지 않는다")
                .anySatisfy(f -> assertThat(f).contains("마무리 인사뿐"));
    }

    @Test
    @DisplayName("실질적 영향을 말하면 통과한다")
    void substantiveImpactPasses() {
        String draft = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원의 원인을 확인 중이며, 확인이 끝날 때까지 "
                + "추가로 청구되는 금액은 없습니다.";
        assertThat(rubric.score(draft, facts()).failed()).isEmpty();
    }

    @Test
    @DisplayName("같은 뜻의 다른 표현도 인정한다 — 오탐 하나가 실험 결론을 뒤집을 뻔했다")
    void recognizesEquivalentPhrasings() {
        String base = "2026-08-30 결제 10,000원이 정상 확인됩니다. 차액 8,888원을 확인 중입니다. ";
        for (String tail : java.util.List.of(
                "청구 금액은 그대로 유지됩니다.",
                "고객님께 미치는 영향은 없습니다.",
                "실제 결제 금액에는 영향을 주지 않습니다.",
                "추가로 청구되는 금액은 없습니다.")) {
            // 원인이 확정된 건이어야 결과를 단정할 수 있다. WEAK 건에 같은 문장을 쓰면
            // <근거 없는 단정>으로 잡히는 것이 맞다(아래 테스트가 그걸 지킨다).
            assertThat(rubric.score(base + tail, decisiveFacts()).failed())
                    .as("표현이 달라도 고객 영향을 말하고 있다: " + tail)
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("원인을 모르는 건에는 고객 영향을 요구하지 않는다 — 요구하면 모델이 지어낸다")
    void doesNotRequireImpactWhenCauseUnknown() {
        // facts() 의 원인은 WEAK(설명되지 않는 차액). 결과를 장담할 수 없다.
        String honest = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원의 원인을 확인 중이며, 확인이 끝나는 대로 안내드리겠습니다.";
        assertThat(rubric.score(honest, facts()).failed())
                .as("모르는 것을 모른다고 쓴 초안을 감점하면, 채우려고 지어낸다")
                .isEmpty();
    }

    @Test
    @DisplayName("원인이 확정된 건에는 여전히 고객 영향을 요구한다")
    void stillRequiresImpactWhenCauseIsDecisive() {
        OrderTimeline t = new OrderTimeline("ORD-3",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "MISMATCH",
                        "대사 AMOUNT_MISMATCH — 내부 10,000 / 외부 9,730", 10_000L, java.util.List.of(10000L, 9730L),
                        java.util.List.<java.time.LocalDate>of())),
                List.of());
        FactPack decisive = FactPack.from(t, CauseSuggestion.decisive(
                ResolveCause.FEE_CALCULATION_DIFF,
                "차액 270원 = 내부 10,000원 x 270 bps", 270L, 10_000L));

        String noImpact = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 270원은 결제 수수료입니다. 확인이 끝나는 대로 안내드리겠습니다.";
        assertThat(rubric.score(noImpact, decisive).failed())
                .as("수수료로 확정된 건은 청구가 그대로라고 말할 수 있고, 말해야 한다")
                .anySatisfy(f -> assertThat(f).contains("고객 영향"));
    }

    @Test
    @DisplayName("원인을 모르는데 <돈은 멀쩡하다>고 단정하면 잡는다 — 심판이 처음 찾아준 결함")
    void catchesUnsupportedMoneyClaim() {
        String draft = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원의 원인을 확인 중입니다. 청구 금액은 그대로 유지됩니다.";
        assertThat(rubric.score(draft, facts()).failed())
                .anySatisfy(f -> assertThat(f).contains("근거 없는 단정"));
    }

    @Test
    @DisplayName("<추가로 청구하지 않겠다>는 약속이라 괜찮다 — 주장과 약속은 다르다")
    void promiseIsNotAnUnsupportedClaim() {
        String draft = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원의 원인을 확인 중이며, 확인이 끝날 때까지 "
                + "추가로 청구되는 금액은 없습니다.";
        assertThat(rubric.score(draft, facts()).failed()).isEmpty();
    }

    @Test
    @DisplayName("원인이 확정된 건에는 <변동이 없다>고 말해도 된다")
    void decisiveCaseMayAssertNoChange() {
        String draft = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원은 결제 수수료이며 청구 금액에는 변동이 없습니다.";
        assertThat(rubric.score(draft, decisiveFacts()).failed()).isEmpty();
    }

    @Test
    @DisplayName("가점은 통과를 바꾸지 않는다 — 필수로 걸면 모델이 채우려고 단정한다")
    void bonusNeverAffectsPassing() {
        String plain = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원의 원인을 확인 중이며, 확인이 끝나는 대로 안내드리겠습니다.";
        DraftRubric.Score s = rubric.score(plain, facts());

        assertThat(s.failed()).as("가점이 없다고 실패가 되면 안 된다").isEmpty();
        assertThat(s.ratio()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("불확실할 때의 약속에 가점 — 같은 만점끼리 가른다")
    void promiseUnderUncertaintyEarnsBonus() {
        String plain = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원의 원인을 확인 중이며, 확인이 끝나는 대로 안내드리겠습니다.";
        String better = plain.replace("확인이 끝나는 대로",
                "확인이 끝날 때까지 추가로 청구되는 금액은 없으며, 결과가 나오는 대로");

        assertThat(rubric.score(plain, facts()).comparable())
                .as("둘 다 6/6 인데 가점이 갈라준다")
                .isLessThan(rubric.score(better, facts()).comparable());
    }

    @Test
    @DisplayName("상관없는 내부 일정을 넣으면 가점을 잃는다")
    void irrelevantInternalScheduleLosesBonus() {
        String clean = "2026-08-30 결제 10,000원이 정상 확인됩니다. "
                + "차액 8,888원의 원인을 확인 중입니다. 확인 후 안내드리겠습니다.";
        String noisy = clean + " 에스크로 자동해제 예정일은 2026-09-06입니다.";

        assertThat(rubric.score(clean, facts()).bonus())
                .contains("상관없는 내부 일정을 넣지 않았다");
        assertThat(rubric.score(noisy, facts()).bonus())
                .doesNotContain("상관없는 내부 일정을 넣지 않았다");
    }

    @Test
    @DisplayName("원인이 확정된 건에는 약속 가점을 주지 않는다 — 거기선 결과를 말하는 게 필수다")
    void noPromiseBonusWhenCauseIsDecisive() {
        String draft = "2026-08-30 결제 10,000원이 정상 확인됩니다. 차액 8,888원은 결제 수수료이며 "
                + "청구 금액에는 변동이 없습니다. 추가로 청구되는 금액은 없습니다.";
        assertThat(rubric.score(draft, decisiveFacts()).bonus())
                .doesNotContain("불확실할 때 추가 청구 없음을 약속했다");
    }
}
