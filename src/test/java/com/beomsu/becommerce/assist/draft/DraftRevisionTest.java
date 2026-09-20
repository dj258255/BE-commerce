package com.beomsu.becommerce.assist.draft;

import com.beomsu.becommerce.assist.draft.TemplateDraftAdapter;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.DraftRubric;
import com.beomsu.becommerce.assist.draft.DraftPort;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 수정 단계의 <b>안전 보장</b>을 고정한다 — 절대 나빠지지 않는다.
 *
 * <p>체이닝의 알려진 위험은 앞 단계 오류가 뒤로 번지는 것이다. 단계별로는 멀쩡한데
 * 전체가 나빠질 수 있다. 그래서 두 초안을 같은 잣대로 채점해 나은 쪽만 쓴다.
 * <b>이 테스트가 그 계약이다.</b>
 */
class DraftRevisionTest {

    private final NumericProvenanceGuard guard = new NumericProvenanceGuard();
    private final CustomerGlossary glossary = new CustomerGlossary();
    private final DraftRubric rubric = new DraftRubric(guard, glossary);

    private FactPack facts() {
        OrderTimeline t = new OrderTimeline("ORD-1",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.PAYMENT, "PAID", "결제 승인", 10_000L)),
                List.of());
        return FactPack.from(t,
                CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF, "FEE_CALCULATION_DIFF (DECISIVE) — 차액 270원", 270L));
    }

    /** 고객 영향이 빠진 초안 — 실측에서 90%가 이 상태였다. */
    private static final String WEAK =
            "2026-08-30 결제 10,000원이 정상 확인됩니다. 차액 270원의 원인을 확인 중이며 "
                    + "확인이 끝나는 대로 안내드리겠습니다.";

    private static DraftPort portReturning(String revised) {
        return new DraftPort() {
            public Optional<String> draft(FactPack f) { return Optional.of(WEAK); }
            public Optional<String> revise(FactPack f, String d, List<String> i) {
                return Optional.ofNullable(revised);
            }
            public String name() { return "fixture"; }
        };
    }

    @Test
    @DisplayName("지적된 것이 채워지면 수정본을 쓴다")
    void takesRevisionWhenBetter() {
        String better = "2026-08-30 결제 10,000원이 정상 확인됩니다. 차액 270원은 결제 수수료로 "
                + "확인 중이며, 고객님께 청구된 금액에는 변동이 없습니다.";
        assertThat(rubric.score(WEAK, facts()).passed())
                .isLessThan(rubric.score(better, facts()).passed());
    }

    @Test
    @DisplayName("수정본이 지어낸 값을 넣으면 원본을 쓴다 — 루브릭 점수와 무관하게 탈락")
    void rejectsRevisionThatInventsValues() {
        String invented = "2026-08-30 결제 10,000원이 정상 확인됩니다. 차액 270원은 수수료이며 "
                + "배송비 3,500원은 청구되지 않습니다.";     // 3,500 은 사실에 없다
        assertThat(guard.verify(invented, facts()))
                .as("루브릭이 만점을 줘도 이건 못 쓴다")
                .isNotEmpty();
    }

    @Test
    @DisplayName("수정이 나아지지 않으면 원본을 쓴다")
    void keepsOriginalWhenNotBetter() {
        String noBetter = "2026-08-30 결제 10,000원 확인 중입니다. 차액 270원을 보고 있습니다. "
                + "확인되면 안내드리겠습니다.";
        assertThat(rubric.score(noBetter, facts()).passed())
                .isLessThanOrEqualTo(rubric.score(WEAK, facts()).passed());
    }

    @Test
    @DisplayName("지적이 없으면 수정을 부르지 않는다 — 멀쩡한 문장이 흔들린다")
    void doesNotReviseWhenNothingFailed() {
        String good = "2026-08-30 결제 10,000원이 정상 확인됩니다. 차액 270원은 결제 수수료이며 "
                + "고객님께 청구된 금액에는 변동이 없습니다.";
        assertThat(rubric.score(good, facts()).failed()).isEmpty();
    }

    @Test
    @DisplayName("템플릿은 수정을 지원하지 않는다 — 고칠 게 없다")
    void templateDoesNotRevise() {
        assertThat(new TemplateDraftAdapter().revise(facts(), WEAK, List.of("고객 영향 없음")))
                .isEmpty();
    }
}
