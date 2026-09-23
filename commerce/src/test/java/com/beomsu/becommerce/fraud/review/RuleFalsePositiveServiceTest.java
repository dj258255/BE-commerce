package com.beomsu.becommerce.fraud.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 규칙별 오탐 집계를 고정한다. <b>어느 규칙을 조일지 고르는 근거</b>라 셈이 틀리면
 * 잘 잡던 규칙을 조이고 못 잡는 규칙을 놔두게 된다.
 */
class RuleFalsePositiveServiceTest {

    private final FraudReviewRepository repository = mock(FraudReviewRepository.class);
    private final RuleFalsePositiveService service = new RuleFalsePositiveService(repository);

    /** 근거 문자열 하나와 사람의 판정 하나 = 심사 한 건. */
    private record Row(String reasons, FraudReviewStatus status)
            implements FraudReviewRepository.JudgedReasons {
        public String getReasons() { return reasons; }
        public FraudReviewStatus getStatus() { return status; }
    }

    private final List<FraudReviewRepository.JudgedReasons> rows = new ArrayList<>();

    /** 심사 {@code times} 건을 같은 근거·같은 판정으로 쌓는다. 여러 번 불러 섞을 수 있다. */
    private void given(String reasons, FraudReviewStatus status, int times) {
        for (int i = 0; i < times; i++) {
            rows.add(new Row(reasons, status));
        }
        when(repository.findJudgedReasons()).thenReturn(rows);
    }

    private RuleFalsePositive find(String rule) {
        return service.byRule().stream()
                .filter(r -> r.rule().equals(rule))
                .findFirst()
                .orElseThrow(() -> new AssertionError(rule + " 이 집계에 없다"));
    }

    @Test
    @DisplayName("발동 횟수가 붙어 있어도 같은 규칙으로 모은다")
    void parameterisedReasonsCollapseToOneRule() {
        given("VELOCITY_EXCEEDED(3)", FraudReviewStatus.APPROVED, 12);
        given("VELOCITY_EXCEEDED(9)", FraudReviewStatus.APPROVED, 8);
        given("VELOCITY_EXCEEDED(4)", FraudReviewStatus.REJECTED, 5);

        RuleFalsePositive velocity = find("VELOCITY_EXCEEDED");
        assertThat(velocity.judged())
                .as("괄호 안 값이 다르다고 쪼개지면 표본이 흩어져 어느 것도 문턱을 못 넘는다")
                .isEqualTo(25);
        assertThat(velocity.falsePositives()).isEqualTo(20);
        assertThat(velocity.ratio()).isEqualTo(0.8);
        assertThat(velocity.actionable())
                .as("판정 25건에 오탐 80% 면 이 규칙은 손볼 이유가 있다")
                .isTrue();
    }

    @Test
    @DisplayName("한 건에 규칙이 둘이면 양쪽에 다 센다")
    void multiRuleReviewCountsForEveryRule() {
        given("HIGH_AMOUNT, IP_VELOCITY_EXCEEDED(11)", FraudReviewStatus.APPROVED, 3);

        assertThat(find("HIGH_AMOUNT").falsePositives()).isEqualTo(3);
        assertThat(find("IP_VELOCITY_EXCEEDED").falsePositives())
                .as("어느 쪽이 진짜 원인인지는 심사 기록에 없다. 한쪽에 몰아주려면 근거를 지어내야 한다")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("같은 규칙이 한 건에서 두 번 나와도 한 건으로 센다")
    void duplicateRuleInOneReviewCountsOnce() {
        given("HIGH_AMOUNT, HIGH_AMOUNT", FraudReviewStatus.REJECTED, 1);

        assertThat(find("HIGH_AMOUNT").judged())
                .as("발동 횟수가 아니라 심사 건수를 센다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("표본이 얇으면 비율이 높아도 손볼 대상이 아니다")
    void thinSampleIsNotActionable() {
        given("HIGH_AMOUNT", FraudReviewStatus.APPROVED, 2);

        RuleFalsePositive high = find("HIGH_AMOUNT");
        assertThat(high.ratio()).isEqualTo(1.0);
        assertThat(high.actionable())
                .as("2건 중 2건은 100% 지만 두 건으로 규칙을 조일 수 없다")
                .isFalse();
    }

    @Test
    @DisplayName("잘 맞히는 규칙도 목록에 남는다 — 빈 화면과 구별해야 한다")
    void accurateRulesStayInTheList() {
        given("BLACKLISTED_CARD", FraudReviewStatus.REJECTED, 30);

        RuleFalsePositive blacklist = find("BLACKLISTED_CARD");
        assertThat(blacklist.ratio()).isZero();
        assertThat(blacklist.actionable()).isFalse();
    }

    @Test
    @DisplayName("손볼 규칙이 먼저 오고, 같은 조건이면 표본이 두꺼운 쪽이 먼저다")
    void actionableRulesComeFirst() {
        given("BLACKLISTED_CARD", FraudReviewStatus.REJECTED, 30);
        given("HIGH_AMOUNT", FraudReviewStatus.APPROVED, 21);
        given("IP_VELOCITY_EXCEEDED", FraudReviewStatus.APPROVED, 40);

        assertThat(service.byRule())
                .extracting(RuleFalsePositive::rule)
                .as("손볼 것 둘이 앞에 오고 그 안에서는 표본이 두꺼운 IP 가 먼저다")
                .containsExactly("IP_VELOCITY_EXCEEDED", "HIGH_AMOUNT", "BLACKLISTED_CARD");
    }

    @Test
    @DisplayName("판정 이력이 없으면 빈 목록이다 — 0 으로 채우지 않는다")
    void noHistoryYieldsEmptyList() {
        when(repository.findJudgedReasons()).thenReturn(List.of());

        assertThat(service.byRule())
                .as("표본이 없는 것을 오탐률 0 으로 적으면 규칙이 멀쩡한 것처럼 보인다")
                .isEmpty();
    }
}
