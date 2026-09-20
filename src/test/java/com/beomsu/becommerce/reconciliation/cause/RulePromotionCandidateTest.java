package com.beomsu.becommerce.reconciliation.cause;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 승격 기준을 고정한다. <b>돈 판정을 자동으로 넘길지 정하는 문턱</b>이라
 * 나중에 누가 숫자만 바꾸면 여기서 걸려야 한다.
 */
class RulePromotionCandidateTest {

    private static RulePromotionCandidate of(long resolved, long total) {
        double share = total == 0 ? 0 : (double) resolved / total;
        return new RulePromotionCandidate("AMOUNT_MISMATCH",
                ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED, resolved, total, share);
    }

    @Test
    @DisplayName("건수와 비율을 둘 다 넘겨야 후보가 된다")
    void needsBothThresholds() {
        assertThat(of(18, 20).promotable())
                .as("18/20 = 90%. 표본도 비율도 넘겼다")
                .isTrue();
    }

    @Test
    @DisplayName("비율이 높아도 표본이 얇으면 규칙이라 부르지 않는다")
    void thinSampleIsNotARule() {
        assertThat(of(2, 2).promotable())
                .as("2건 중 2건은 100%지만 두 건으로 돈 판정을 자동화할 수 없다")
                .isFalse();
    }

    @Test
    @DisplayName("표본이 두꺼워도 사람 판단이 갈리면 후보가 아니다")
    void splitJudgementIsNotARule() {
        assertThat(of(60, 100).promotable())
                .as("60%면 열 건에 네 건이 다른 답이다. 자동으로 넘기면 그 넷이 장부에 남는다")
                .isFalse();
    }

    @Test
    @DisplayName("문턱 바로 아래는 안 넘긴다")
    void justBelowThresholdFails() {
        assertThat(of(9, 10).promotable())
                .as("90%는 넘지만 9건이라 표본 문턱에 걸린다")
                .isFalse();
        assertThat(of(89, 100).promotable())
                .as("89%는 표본은 넘지만 비율에 걸린다")
                .isFalse();
    }
}
