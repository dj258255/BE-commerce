package com.beomsu.becommerce.assist.fraudreview;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 규칙 값을 모델에게 어떻게 넘기는지 고정한다.
 *
 * <p><b>이 테스트가 있는 이유는 실측이다.</b> 값을 {@code 980000/1000000} 꼴로 그대로 주니
 * 모델이 앞 숫자를 임계값으로 읽어 "임계값 980,000원" 이라고 썼다. {@code 3/1m} 은
 * <b>"1만 건 기준"</b> 으로 읽었다. 형식을 바꾼 것이지 프롬프트로 타이른 것이 아니다.
 */
@DisplayName("심사 초안 프롬프트 — 규칙 값에 이름을 붙인다")
class FraudReviewPromptBuilderTest {

    @Test
    @DisplayName("임계 근처 규칙은 결제 금액과 임계를 갈라 준다 — 붙여 주면 앞 숫자를 임계로 읽는다")
    void nearThresholdIsSplit() {
        String out = FraudReviewPromptBuilder.explain("NEAR_THRESHOLD", "980000/1000000");

        assertThat(out).isEqualTo("결제 금액 980,000원, 고액 임계 1,000,000원");
        assertThat(out).as("갈라 놓지 않으면 어느 쪽이 임계인지 문장에 없다").contains("임계");
    }

    @Test
    @DisplayName("속도 규칙의 뒤쪽은 백만이 아니라 시간 창이다")
    void velocityWindowIsTimeNotCount() {
        assertThat(FraudReviewPromptBuilder.explain("VELOCITY_EXCEEDED", "3/1m"))
                .isEqualTo("최근 1분 안에 3건");
        assertThat(FraudReviewPromptBuilder.explain("VELOCITY_EXCEEDED", "6/1h"))
                .isEqualTo("최근 1시간 안에 6건");
    }

    @Test
    @DisplayName("금액 규칙은 원 단위를 붙인다 — 500 을 점수로 읽은 적이 있다")
    void amountRulesCarryTheUnit() {
        assertThat(FraudReviewPromptBuilder.explain("MICRO_PROBE", "500")).isEqualTo("소액 결제 500원");
        assertThat(FraudReviewPromptBuilder.explain("HIGH_AMOUNT", "840000"))
                .isEqualTo("결제 금액 840,000원");
    }

    @Test
    @DisplayName("개수 규칙은 개를 붙인다")
    void countRulesCarryTheUnit() {
        assertThat(FraudReviewPromptBuilder.explain("DEVICE_CHURN", "6")).isEqualTo("쓰인 기기 6개");
        assertThat(FraudReviewPromptBuilder.explain("IP_CHURN", "4")).isEqualTo("쓰인 IP 4개");
    }

    @Test
    @DisplayName("모르는 규칙은 값을 그대로 준다 — 억지로 풀면 없는 뜻을 붙인다")
    void unknownRuleIsLeftAlone() {
        assertThat(FraudReviewPromptBuilder.explain("SOMETHING_NEW", "7/9")).isEqualTo("값 7/9");
    }

    @Test
    @DisplayName("모양이 다르면 손대지 않는다 — 쪼개다 틀리느니 원문이 낫다")
    void malformedDetailIsLeftAlone() {
        assertThat(FraudReviewPromptBuilder.explain("NEAR_THRESHOLD", "980000")).isEqualTo("값 980000");
        assertThat(FraudReviewPromptBuilder.explain("MICRO_PROBE", "약간")).isEqualTo("소액 결제 약간원");
    }
}
