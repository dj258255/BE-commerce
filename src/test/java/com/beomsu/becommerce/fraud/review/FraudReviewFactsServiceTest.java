package com.beomsu.becommerce.fraud.review;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
import com.beomsu.becommerce.fraud.internal.FdsDecision;
import com.beomsu.becommerce.fraud.internal.FraudResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 심사 화면에 내려갈 사실을 고정한다.
 *
 * <p><b>얇은 표본을 비율로 내리지 않는 것</b>이 이 테스트의 요점이다. 3건 중 1건을 33% 로
 * 내려보내면 심사자도 모델도 그 숫자를 근거로 쓴다. 같은 이유로
 * {@code RuleFalsePositive.MIN_JUDGED} 를 둔 것인데, 그 판단이 화면까지 오는지는 별개다.
 */
@DisplayName("심사 사실 조립 — 얇은 표본은 비율로 내리지 않는다")
class FraudReviewFactsServiceTest {

    private final FraudReviewRepository repository = mock(FraudReviewRepository.class);
    private final RuleFalsePositiveService ruleStats = mock(RuleFalsePositiveService.class);
    private final FraudReviewFactsService service = new FraudReviewFactsService(repository, ruleStats);

    private record Row(String reasons, FraudReviewStatus status)
            implements FraudReviewRepository.JudgedReasons {
        public String getReasons() { return reasons; }
        public FraudReviewStatus getStatus() { return status; }
    }

    private long nextId = 1L;

    /**
     * 저장된 것처럼 id 를 박아 준다.
     *
     * <p>{@code flagged} 는 저장 전 객체라 id 가 {@code null} 이다. 실제로는 저장소가 돌려주는
     * 항목이라 id 가 늘 있는데, 그걸 안 넣으면 {@code long} 으로 언박싱하다 터진다.
     */
    private FraudReview flagged(String cardKey, String... reasons) {
        var r = FraudReview.flagged("ORD-1", 11L, cardKey, 320_000L,
                new FraudResult(60, FdsDecision.REVIEW, List.of(reasons)));
        ReflectionTestUtils.setField(r, "id", nextId++);
        return r;
    }

    private void givenTarget(FraudReview target) {
        when(repository.findById(1L)).thenReturn(Optional.of(target));
        when(repository.findByCardKey(anyString())).thenReturn(List.of(target));
    }

    @Test
    @DisplayName("판정 20건을 넘긴 규칙만 비율을 싣는다")
    void thinSampleCarriesCountOnly() {
        givenTarget(flagged("tgen_abcd12345678wxyz", "HIGH_AMOUNT", "IP_VELOCITY_EXCEEDED(9)"));
        when(ruleStats.byRule()).thenReturn(List.of(
                new RuleFalsePositive("HIGH_AMOUNT", 34, 6, 0.85),
                new RuleFalsePositive("IP_VELOCITY_EXCEEDED", 1, 2, 1.0 / 3)));

        var facts = service.factsOf(1L).orElseThrow();

        var high = facts.firedRules().stream()
                .filter(r -> r.name().equals("HIGH_AMOUNT")).findFirst().orElseThrow();
        assertThat(high.judged()).isEqualTo(40);
        assertThat(high.normalRatio()).isEqualTo(0.85);

        var ip = facts.firedRules().stream()
                .filter(r -> r.name().equals("IP_VELOCITY_EXCEEDED")).findFirst().orElseThrow();
        assertThat(ip.judged()).isEqualTo(3);
        assertThat(ip.normalRatio())
                .as("판정 3건을 33%%로 내려보내면 그 숫자가 근거로 쓰인다")
                .isNull();
        assertThat(ip.detail()).as("괄호 안 발동 횟수는 화면에 남긴다").isEqualTo("9");
    }

    @Test
    @DisplayName("카드 키는 앞4·뒤4만 나간다 — 원본은 모듈 밖으로 안 간다")
    void cardKeyIsMasked() {
        givenTarget(flagged("tgen_abcd12345678wxyz", "HIGH_AMOUNT"));
        when(ruleStats.byRule()).thenReturn(List.of());

        var facts = service.factsOf(1L).orElseThrow();

        assertThat(facts.maskedCardKey()).isEqualTo("tgen****wxyz");
        assertThat(facts.maskedCardKey()).doesNotContain("abcd12345678");
    }

    @Test
    @DisplayName("같은 카드 이력은 판정이 끝난 건만 센다 — 대기 건을 넣으면 밀릴수록 숫자가 커진다")
    void sameCardTallyExcludesPending() {
        var target = flagged("tgen_abcd12345678wxyz", "HIGH_AMOUNT");
        var judgedNormal = flagged("tgen_abcd12345678wxyz", "HIGH_AMOUNT");
        judgedNormal.approve("me");
        var judgedFraud = flagged("tgen_abcd12345678wxyz", "HIGH_AMOUNT");
        judgedFraud.reject("me");
        var stillPending = flagged("tgen_abcd12345678wxyz", "HIGH_AMOUNT");

        when(repository.findById(1L)).thenReturn(Optional.of(target));
        when(repository.findByCardKey(anyString()))
                .thenReturn(new ArrayList<>(List.of(target, judgedNormal, judgedFraud, stillPending)));
        when(ruleStats.byRule()).thenReturn(List.of());

        var facts = service.factsOf(1L).orElseThrow();

        assertThat(facts.sameCardJudged()).isEqualTo(2);
        assertThat(facts.sameCardApproved()).isEqualTo(1);
    }

    @Test
    @DisplayName("없는 심사는 빈 값이다")
    void missingIsEmpty() {
        when(repository.findById(9L)).thenReturn(Optional.empty());
        assertThat(service.factsOf(9L)).isEmpty();
    }

    @Test
    @DisplayName("근거가 비면 발동 규칙도 비고, 예외를 내지 않는다")
    void blankReasonsGiveNoRules() {
        givenTarget(flagged("tgen_abcd12345678wxyz"));
        when(ruleStats.byRule()).thenReturn(List.of());

        assertThat(service.factsOf(1L).orElseThrow().firedRules()).isEmpty();
    }

    /** 집계에 없는 규칙이 근거에 있으면 판정 0건으로 내려간다. 비율은 당연히 없다. */
    @Test
    @DisplayName("아직 한 번도 판정되지 않은 규칙은 0건으로 내려간다")
    void unjudgedRuleGoesDownAsZero() {
        givenTarget(flagged("tgen_abcd12345678wxyz", "BLACKLISTED_CARD"));
        when(ruleStats.byRule()).thenReturn(List.of());

        var rule = service.factsOf(1L).orElseThrow().firedRules().getFirst();
        assertThat(rule.name()).isEqualTo("BLACKLISTED_CARD");
        assertThat(rule.judged()).isZero();
        assertThat(rule.normalRatio()).isNull();
    }

    @SuppressWarnings("unused")
    private static final FraudReviewRepository.JudgedReasons UNUSED = new Row("", null);
}
