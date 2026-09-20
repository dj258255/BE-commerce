package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사실을 넓혀 쓴 초안을 잡는다. <b>실측에서 나온 두 모양만 본다.</b>
 *
 * <p>출처 검증은 숫자만 보므로 이 둘을 통과시킨다. 3 도 맞고 0 도 맞기 때문이다.
 */
@DisplayName("사실 확대 검사 — 숫자는 맞는데 뜻을 넓힌 자리")
class FactWideningGuardTest {

    private final FactWideningGuard guard = new FactWideningGuard();

    private static FraudReviewFacts facts(long sameCardJudged, FraudReviewFacts.FiredRule... rules) {
        return new FraudReviewFacts(7L, "ORD-1", 11L, "3ad2****2a35", 205_000L, 60, "REVIEW",
                Instant.parse("2026-09-09T02:00:00Z"), List.of(rules), sameCardJudged, 0);
    }

    private static FraudReviewFacts.FiredRule rule(String name, String detail) {
        return new FraudReviewFacts.FiredRule(name, detail, 0, null);
    }

    @Test
    @DisplayName("정확한 수를 이상으로 넓히면 잡는다 — 규칙이 준 값은 하한이 아니다")
    void exactCountWidenedToLowerBound() {
        var f = facts(0, rule("VELOCITY_EXCEEDED", "3/1m"));

        assertThat(guard.verify("1분 내에 3건 이상의 결제가 발생했다.", f))
                .singleElement().asString().contains("3건 이상");
    }

    @Test
    @DisplayName("그대로 쓰면 통과한다")
    void exactCountStatedAsIsPasses() {
        var f = facts(0, rule("VELOCITY_EXCEEDED", "3/1m"));

        assertThat(guard.verify("최근 1분 안에 3건의 결제가 있었다.", f)).isEmpty();
    }

    @Test
    @DisplayName("사실에 없는 수의 이상은 안 잡는다 — 그건 출처 검증이 맡는다")
    void unknownCountIsLeftToTheProvenanceGuard() {
        var f = facts(0, rule("VELOCITY_EXCEEDED", "3/1m"));

        assertThat(guard.verify("9건 이상이 발생했다.", f)).isEmpty();
    }

    @Test
    @DisplayName("심사를 결제로 넓히면 잡는다 — 첫 거래인 것처럼 읽힌다")
    void reviewHistoryWidenedToPaymentHistory() {
        var f = facts(0, rule("DEVICE_CHURN", "6"));

        assertThat(guard.verify("같은 카드로 이전 결제가 없다.", f))
                .singleElement().asString().contains("판정이 끝난 심사");
        assertThat(guard.verify("해당 카드의 과거 거래 내역이 없습니다.", f)).hasSize(1);
        assertThat(guard.verify("같은 카드로 구매 기록이 없습니다.", f)).hasSize(1);
    }

    @Test
    @DisplayName("심사라고 쓰면 통과한다")
    void reviewHistoryStatedAsIsPasses() {
        var f = facts(0, rule("DEVICE_CHURN", "6"));

        assertThat(guard.verify("판정이 끝난 지난 심사가 없습니다.", f)).isEmpty();
    }

    @Test
    @DisplayName("지난 심사가 있으면 이 검사를 안 돌린다 — 그때는 그 문장이 안 나온다")
    void notCheckedWhenPastReviewsExist() {
        var f = facts(3, rule("DEVICE_CHURN", "6"));

        assertThat(guard.verify("같은 카드로 이전 결제가 없다.", f)).isEmpty();
    }

    @Test
    @DisplayName("빈 초안과 사실 없음은 통과시킨다 — 없는 것을 잡을 수는 없다")
    void emptyInputPasses() {
        assertThat(guard.verify(null, facts(0))).isEmpty();
        assertThat(guard.verify("  ", facts(0))).isEmpty();
        assertThat(guard.verify("아무 말", null)).isEmpty();
    }
}
