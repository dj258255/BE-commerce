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
 * 케이스별 예시 선택.
 *
 * <p>지키려는 것은 하나다 — <b>기록이 없는 건에 "청구는 그대로"라는 예시를 주지 않는다.</b>
 * 그 하나가 근거 없는 단정의 절반을 만들었다(13 문서 실험 9).
 */
class DraftExamplesTest {

    private final DraftExamples examples = new DraftExamples();

    private FactPack with(String causeHint) {
        OrderTimeline t = new OrderTimeline("ORD-1",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "R", "대사", 10_000L)),
                List.of());
        return FactPack.from(t, suggestion(causeHint));
    }

    /**
     * 테스트가 쓰는 힌트 문자열을 제안 객체로 되돌린다.
     *
     * <p>본문은 이제 제안을 <b>구조로</b> 받는다. 이 테스트는 "어떤 원인·확신이면 어떤 예시가
     * 나오는가"를 보는 것이라, 문자열을 그대로 두고 여기서만 되돌려 준다.
     */
    private CauseSuggestion suggestion(String causeHint) {
        if (causeHint == null) {
            return null;
        }
        String[] head = causeHint.split(" — ", 2);
        String evidence = head.length > 1 ? head[1] : "";
        String name = head[0].replaceAll("\\s*\\(.*", "").trim();
        var confidence = head[0].contains("(WEAK)") ? CauseSuggestion.Confidence.WEAK
                : head[0].contains("(LIKELY)") ? CauseSuggestion.Confidence.LIKELY
                : CauseSuggestion.Confidence.DECISIVE;
        try {
            return new CauseSuggestion(ResolveCause.valueOf(name), confidence, evidence,
                    java.util.Set.of());
        } catch (IllegalArgumentException e) {
            // 원인 목록에 없는 이름 = 원인을 모른다는 뜻이다. 확신도 함께 내려야 맞다.
            return new CauseSuggestion(ResolveCause.OTHER, CauseSuggestion.Confidence.WEAK,
                    evidence, java.util.Set.of());
        }
    }

    @Test
    @DisplayName("기록이 없는 건에는 <청구가 그대로>라는 예시를 주지 않는다")
    void noRecordCaseDoesNotShowChargeUnchanged() {
        String p = examples.forCase(with("INTERNAL_RECORD_LOST (LIKELY) — 외부에만 존재"));
        assertThat(p)
                .contains("저희 기록에서 찾지 못한")
                .contains("카드사 승인 내역을 보내주시면")
                .doesNotContain("고객님께 청구된 금액에는 변동이 없습니다");
        assertThat(p).as("무엇을 쓰면 안 되는지도 알려준다").contains("근거가 없습니다");
    }

    @Test
    @DisplayName("원인이 확정된 건에는 결과를 말하는 예시를 준다")
    void decisiveCaseShowsImpact() {
        String p = examples.forCase(with("FEE_CALCULATION_DIFF (DECISIVE) — 차액 270원"));
        assertThat(p)
                .contains("원인이 확인된 경우")
                .contains("청구된 금액에는 변동이 없습니다");
    }

    @Test
    @DisplayName("원인을 모르면 <모른다고 쓰는> 예시를 준다 — 기본값이다")
    void unknownCauseTeachesAbstention() {
        for (String hint : List.of("SUSPECTED_TAMPERING (WEAK) — 설명되지 않는다", "", "AMOUNT_MISMATCH")) {
            String p = examples.forCase(with(hint.isEmpty() ? null : hint));
            assertThat(p)
                    .as("hint=" + hint)
                    .contains("원인을 아직 모르는 경우")
                    .contains("결과를 단정하지 마십시오");
        }
    }

    @Test
    @DisplayName("파일 지연은 결제 자체가 무사하다고 말하는 예시")
    void fileDelayShowsPaymentIsFine() {
        assertThat(examples.forCase(with("PG_FILE_DELAY (LIKELY) — 아직 파일에 안 실렸다")))
                .contains("결제 자체에는 문제가 없습니다");
    }

    @Test
    @DisplayName("나쁜 예는 어느 경우에나 함께 준다 — 피할 것은 보여주는 쪽이 전달된다")
    void badExampleAlwaysIncluded() {
        for (String hint : List.of("FEE_CALCULATION_DIFF (DECISIVE)", "INTERNAL_RECORD_LOST (LIKELY)",
                "PG_FILE_DELAY (LIKELY)", "SUSPECTED_TAMPERING (WEAK)")) {
            assertThat(examples.forCase(with(hint)))
                    .as("hint=" + hint)
                    .contains("이렇게 쓰지 마십시오")
                    .contains("근거가 없는 것은 단정하지 마십시오");
        }
    }

    @Test
    @DisplayName("예시는 하나만 싣는다 — 여러 개면 안 맞는 쪽을 따라 쓴다")
    void onlyOneGoodExample() {
        String p = examples.forCase(with("INTERNAL_RECORD_LOST (LIKELY)"));
        assertThat(p.split("좋은 초안:", -1).length - 1)
                .as("좋은 예시는 정확히 하나여야 한다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("예시 자체에 내부 코드를 적지 않는다 — 금지어를 보여주면 모델이 따라 쓴다")
    void examplesContainNoInternalCodes() {
        CustomerGlossary glossary = new CustomerGlossary();
        for (String hint : List.of("FEE_CALCULATION_DIFF (DECISIVE)", "INTERNAL_RECORD_LOST (LIKELY)",
                "PG_FILE_DELAY (LIKELY)", "SUSPECTED_TAMPERING (WEAK)")) {
            // 예시 본문(초안 예시 부분)에 대문자 코드가 있으면 모델이 베낀다.
            // 실측에서 용어 누출 3건이 전부 나쁜 예시의 PAID 토큰이었다.
            assertThat(glossary.findJargon(examples.forCase(with(hint))))
                    .as("hint=" + hint + " — 예시에 남은 내부 코드")
                    .isEmpty();
        }
    }
}
