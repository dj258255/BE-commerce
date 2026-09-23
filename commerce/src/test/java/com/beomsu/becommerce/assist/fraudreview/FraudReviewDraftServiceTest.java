package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.fraud.FraudReviewFacts;
import com.beomsu.becommerce.fraud.FraudReviewFactsPort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>가드가 실제로 무엇을 버리는지</b>를 고정한다.
 *
 * <p>이 테스트가 없으면 모델을 켠 날에야 가드가 도는지 알게 된다. 상황 3.4 에서 적은 것과 같은
 * 이유로, 모델 어댑터가 붙는 날 검사를 새로 만드는 게 아니라 <b>이미 돌고 있어야</b> 한다.
 */
@DisplayName("FDS 심사 초안 — 지어낸 숫자를 버리고 템플릿으로 떨어진다")
class FraudReviewDraftServiceTest {

    private static final long REVIEW_ID = 7L;
    private static final long AMOUNT = 320_000L;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final TemplateFraudReviewAdapter template = new TemplateFraudReviewAdapter();
    private final NumericProvenanceGuard guard = new NumericProvenanceGuard();

    private static FraudReviewFacts facts() {
        return new FraudReviewFacts(
                REVIEW_ID, "ORD-1", 11L, "tgen****9f2a", AMOUNT, 60, "REVIEW",
                Instant.parse("2026-09-08T02:00:00Z"),
                List.of(new FraudReviewFacts.FiredRule("HIGH_AMOUNT", null, 40, 0.85),
                        new FraudReviewFacts.FiredRule("IP_VELOCITY_EXCEEDED", "9", 3, null)),
                4, 3);
    }

    private FraudReviewDraftService serviceWith(FraudReviewDraftPort primary) {
        FraudReviewFactsPort port = id -> id == REVIEW_ID ? Optional.of(facts()) : Optional.empty();
        // provider 이름으로 고르므로 그 이름을 그대로 준다.
        return new FraudReviewDraftService(port, List.of(primary, template), template,
                guard, new com.beomsu.becommerce.assist.draft.AmountCoverageGuard(), new FactWideningGuard(), registry, primary.name());
    }

    /** 고정된 문장을 돌려주는 가짜 모델. */
    private static FraudReviewDraftPort fixed(String text) {
        return new FraudReviewDraftPort() {
            @Override public Optional<String> draft(FraudReviewFacts f) {
                return Optional.ofNullable(text);
            }
            @Override public String name() { return "fake"; }
        };
    }

    @Test
    @DisplayName("없는 심사는 빈 값이다 — 초안을 못 만든 것과 구별해야 한다")
    void missingReviewIsEmpty() {
        assertThat(serviceWith(fixed("아무 말")).draftFor(999L)).isEmpty();
    }

    @Test
    @DisplayName("사실에 있는 금액만 쓴 초안은 그대로 나간다")
    void groundedDraftPasses() {
        var draft = serviceWith(fixed("결제 320,000원 건입니다. 같은 카드 이력을 확인하십시오."))
                .draftFor(REVIEW_ID).orElseThrow();

        assertThat(draft.source()).isEqualTo("fake");
        assertThat(draft.notes()).isEmpty();
        assertThat(draft.text()).contains("320,000원");
    }

    @Test
    @DisplayName("출처에 없는 금액을 쓰면 초안을 버리고 템플릿으로 떨어진다")
    void inventedAmountFallsBackToTemplate() {
        var draft = serviceWith(fixed("결제 302,000원 건입니다."))
                .draftFor(REVIEW_ID).orElseThrow();

        assertThat(draft.source()).isEqualTo("template");
        assertThat(draft.notes()).contains("guard_rejected");
        // 떨어진 뒤에도 화면은 비지 않는다
        assertThat(draft.present()).isTrue();
        assertThat(draft.text()).contains("HIGH_AMOUNT");
    }

    @Test
    @DisplayName("모델이 죽어도 템플릿이 나간다 — 심사 화면이 비면 안 된다")
    void deadModelFallsBackToTemplate() {
        var draft = serviceWith(fixed(null)).draftFor(REVIEW_ID).orElseThrow();

        assertThat(draft.source()).isEqualTo("template");
        assertThat(draft.notes()).contains("no_draft");
        assertThat(draft.present()).isTrue();
    }

    @Test
    @DisplayName("폴백이 조용히 일어나지 않는다 — 지표에 남는다")
    void fallbackIsCounted() {
        serviceWith(fixed("결제 999,999원")).draftFor(REVIEW_ID);

        var counter = registry.find(FraudReviewDraftService.METRIC)
                .tag("outcome", "guard_rejected").counter();
        assertThat(counter).isNotNull();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Nested
    @DisplayName("템플릿")
    class Template {

        @Test
        @DisplayName("표본이 얇은 규칙은 비율 대신 건수를 적는다 — 3건 중 1건을 33%로 주면 근거처럼 읽힌다")
        void thinSampleShowsCountNotRatio() {
            String text = template.draft(facts()).orElseThrow();

            assertThat(text).contains("HIGH_AMOUNT").contains("85%가 정상으로 닫혔습니다");
            assertThat(text).contains("IP_VELOCITY_EXCEEDED").contains("판정 표본이 3건");
            assertThat(text).doesNotContain("33%");
        }

        @Test
        @DisplayName("발동한 규칙이 없으면 만들지 않는다")
        void noRuleNoDraft() {
            var empty = new FraudReviewFacts(1L, "ORD-2", 1L, "****", 1000L, 0, "ALLOW",
                    Instant.now(), List.of(), 0, 0);
            assertThat(template.draft(empty)).isEmpty();
        }

        @Test
        @DisplayName("판정을 쓰지 않는다 — 그 문장이 곧 심사자의 근거가 된다")
        void templateDoesNotJudge() {
            String text = template.draft(facts()).orElseThrow();

            assertThat(text)
                    .doesNotContain("정상으로 보")
                    .doesNotContain("부정거래")
                    .doesNotContain("차단");
        }
    }

    /** 금액을 빠뜨린 초안을 내고, 되묻기에서 채워 주는 가짜 모델. */
    private static FraudReviewDraftPort forgetsAmountThenFixes(String first, String revised) {
        return new FraudReviewDraftPort() {
            @Override public Optional<String> draft(FraudReviewFacts f) { return Optional.of(first); }
            @Override public Optional<String> revise(FraudReviewFacts f, String o, java.util.List<String> issues) {
                return Optional.ofNullable(revised);
            }
            @Override public String name() { return "fake-forgetful"; }
        };
    }

    @Test
    @DisplayName("금액이 빠지면 되묻어 채운다 — 지어낸 값을 버리는 검사와 방향이 반대다")
    void missingAmountIsRevised() {
        String withAmount = "심사 대상 결제는 " + java.text.NumberFormat
                .getNumberInstance(java.util.Locale.KOREA).format(AMOUNT) + "원입니다.";

        var draft = serviceWith(forgetsAmountThenFixes("금액이 큰 결제입니다.", withAmount))
                .draftFor(REVIEW_ID).orElseThrow();

        assertThat(draft.text()).isEqualTo(withAmount);
        assertThat(draft.source()).isEqualTo("fake-forgetful");
    }

    @Test
    @DisplayName("되묻어도 안 채워지면 원본을 쓴다 — 되묻기가 멀쩡한 문장을 흔들면 안 된다")
    void unhelpfulReviseKeepsTheOriginal() {
        var draft = serviceWith(forgetsAmountThenFixes("금액이 큰 결제입니다.", "여전히 금액이 없습니다."))
                .draftFor(REVIEW_ID).orElseThrow();

        assertThat(draft.text()).isEqualTo("금액이 큰 결제입니다.");
    }

    @Test
    @DisplayName("금액이 이미 있으면 되묻지 않는다 — 고칠 게 없는데 다시 쓰게 하면 문장이 흔들린다")
    void draftWithAmountIsNotRevised() {
        String ok = "심사 대상 결제는 " + java.text.NumberFormat
                .getNumberInstance(java.util.Locale.KOREA).format(AMOUNT) + "원입니다.";

        var draft = serviceWith(forgetsAmountThenFixes(ok, "이 값은 쓰이면 안 된다"))
                .draftFor(REVIEW_ID).orElseThrow();

        assertThat(draft.text()).isEqualTo(ok);
    }
}
