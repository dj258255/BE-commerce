package com.beomsu.becommerce.assist.draft;

import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.DraftRubric;
import com.beomsu.becommerce.assist.draft.CustomerGlossary;
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
 * 밖에서 받아온 초안을 <b>같은 잣대</b>로 채점한다.
 *
 * <p><b>왜 필요한가</b>: 앱이 부를 수 있는 모델은 로컬뿐이다. 생성형 AI 는 아직 금융권
 * 망분리 예외가 아니고, 외부 API 를 켜려면 키가 필요하다. 구독은 API 접근을 포함하지 않는다.
 *
 * <p>그런데 <b>더 좋은 모델이 어떤 초안을 쓰는지</b>는 궁금하고, 그걸 보는 데 앱이 직접
 * 호출할 필요는 없다. 프롬프트를 꺼내 어디서든 돌리고, 받은 초안을 되돌리면 된다.
 *
 * <p><b>같은 검사를 태우는 것이 요점이다.</b> 안 그러면 "저 모델이 더 잘 쓰더라"가
 * 인상으로만 남는다. 이 프로젝트에서 인상이 틀린 적이 여러 번 있었다.
 */
class ImportedDraftScoringTest {

    private final NumericProvenanceGuard guard = new NumericProvenanceGuard();
    private final CustomerGlossary glossary = new CustomerGlossary();
    private final DraftRubric rubric = new DraftRubric(guard, glossary);

    private FactPack facts() {
        OrderTimeline t = new OrderTimeline("ORD-1",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "MISMATCH",
                        "대사 AMOUNT_MISMATCH — 내부 10,000 / 외부 9,730", 10_000L, java.util.List.of(10000L, 9730L),
                        java.util.List.<java.time.LocalDate>of())),
                List.of());
        return FactPack.from(t, CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF,
                "차액 270원 = 내부 10,000원 x 270 bps", 270L, 10_000L));
    }

    @Test
    @DisplayName("밖에서 온 초안도 지어낸 숫자면 반려된다 — 출처가 좋다고 봐주지 않는다")
    void importedDraftIsGuardedToo() {
        String draft = "2026-08-30 결제 10,000원이 확인됩니다. 배송비 3,500원이 청구되었습니다.";
        assertThat(guard.verify(draft, facts()))
                .anySatisfy(m -> assertThat(m).contains("3,500"));
    }

    @Test
    @DisplayName("밖에서 온 초안도 같은 루브릭으로 잰다 — 안 그러면 비교가 성립하지 않는다")
    void importedDraftIsScoredIdentically() {
        String good = "2026-08-30 결제 승인 10,000원이 정상 확인됩니다. "
                + "결제사 정산 내역에는 수수료가 차감된 9,730원으로 기록되어 있으며, "
                + "차액 270원은 결제 수수료입니다. 고객님께 청구된 금액에는 변동이 없습니다.";
        String weak = "2026-08-30 결제 승인 10,000원 확인 중입니다. "
                + "차액 270원을 보고 있습니다. 확인되면 안내드리겠습니다.";

        assertThat(rubric.score(good, facts()).failed()).isEmpty();
        assertThat(rubric.score(weak, facts()).failed())
                .as("확정된 원인인데 고객 영향을 말하지 않았다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("내부 용어는 밖에서 와도 잡힌다")
    void jargonCaughtRegardlessOfSource() {
        assertThat(glossary.findJargon("주문 상태 PAID 로 확인됩니다."))
                .containsExactly("PAID");
    }

    @Test
    @DisplayName("빈 초안은 채점하지 않는다 — 없는 것을 0점으로 세면 모델을 잘못 깎는다")
    void blankImportIsNotScoredAsZero() {
        assertThat(rubric.score("  ", facts()).failed()).containsExactly("초안 없음");
    }
}
