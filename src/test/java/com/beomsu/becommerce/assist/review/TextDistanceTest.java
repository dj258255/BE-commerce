package com.beomsu.becommerce.assist.review;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 편집 거리 — "초안을 얼마나 고쳤나"를 숫자로 만드는 부분.
 *
 * <p>이 숫자가 틀리면 실험 전체가 틀린 결론을 낸다. 특히 한국어에서
 * <b>어절 단위로 자르면 안 되는 이유</b>를 여기서 고정한다.
 */
class TextDistanceTest {

    @Test
    @DisplayName("그대로 쓰면 0")
    void identicalIsZero() {
        assertThat(TextDistance.editRatio("결제 10,000원이 승인되었습니다.",
                "결제 10,000원이 승인되었습니다.")).isZero();
    }

    @Test
    @DisplayName("공백·줄바꿈 차이는 고친 것으로 세지 않는다")
    void whitespaceIsNotAnEdit() {
        assertThat(TextDistance.editRatio("결제 10,000원이\n  승인되었습니다.",
                "결제 10,000원이 승인되었습니다.")).isZero();
    }

    @Test
    @DisplayName("어미만 다듬은 것은 작게 나온다 — 어절 단위로 세면 이게 크게 잡힌다")
    void inflectionChangeIsSmall() {
        double r = TextDistance.editRatio(
                "결제 10,000원이 정상 확인됩니다.",
                "결제 10,000원이 정상 확인되었습니다.");
        assertThat(r).isLessThan(0.2);
    }

    @Test
    @DisplayName("통째로 새로 쓰면 1에 가깝다")
    void fullRewriteIsNearOne() {
        double r = TextDistance.editRatio(
                "에스크로 보류 자동 해제 예정일은 2026-09-06입니다.",
                "금액 차이 8,888원의 원인을 확인 중입니다.");
        assertThat(r).isGreaterThan(0.6);
    }

    @Test
    @DisplayName("초안이 비어 있으면 사람이 전부 쓴 것 — 1")
    void emptyDraftMeansFullyWritten() {
        assertThat(TextDistance.editRatio("", "사람이 직접 쓴 답변입니다."))
                .isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("긴 쪽으로 나눈다 — 1을 넘으면 해석이 깨진다")
    void ratioNeverExceedsOne() {
        assertThat(TextDistance.editRatio("짧다", "아주 아주 아주 아주 긴 문장입니다 여러 문장"))
                .isLessThanOrEqualTo(1.0);
    }
}
