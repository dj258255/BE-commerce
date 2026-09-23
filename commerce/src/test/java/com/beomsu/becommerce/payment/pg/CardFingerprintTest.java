package com.beomsu.becommerce.payment.pg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 지문을 고정한다. <b>이 값이 흔들리면 같은 카드가 갈라져 창이 다시 빈다.</b>
 *
 * <p>그리고 <b>원문이 남지 않는 것</b>이 절반이다. 마스킹된 값이라도 그대로 적으면 카드에 붙는
 * 데이터를 저장하는 것이 된다.
 */
@DisplayName("카드 지문 — 같은 카드를 결제 여러 건에 걸쳐 묶는다")
class CardFingerprintTest {

    private static final String MASKED = "12345678****123*";

    @Test
    @DisplayName("같은 카드는 늘 같은 지문이다 — 갈라지면 창이 빈다")
    void sameCardGivesSameKey() {
        assertThat(CardFingerprint.of(MASKED, "3K"))
                .isEqualTo(CardFingerprint.of(MASKED, "3K"));
    }

    @Test
    @DisplayName("발급사가 다르면 다른 지문이다")
    void issuerSeparatesCards() {
        assertThat(CardFingerprint.of(MASKED, "3K"))
                .isNotEqualTo(CardFingerprint.of(MASKED, "4V"));
    }

    @Test
    @DisplayName("번호가 다르면 다른 지문이다")
    void numberSeparatesCards() {
        assertThat(CardFingerprint.of(MASKED, "3K"))
                .isNotEqualTo(CardFingerprint.of("87654321****999*", "3K"));
    }

    @Test
    @DisplayName("원문이 지문에 남지 않는다 — 마스킹 값이라도 그대로 적으면 안 된다")
    void inputIsNotRecoverable() {
        String key = CardFingerprint.of(MASKED, "3K");

        assertThat(key).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(key).doesNotContain("12345678").doesNotContain("3K");
    }

    @Test
    @DisplayName("구분자를 넣어 자리 옮김을 막는다 — 붙여 쓰면 서로 다른 카드가 같은 키가 된다")
    void delimiterPreventsCollision() {
        // "12" + "34" 와 "1" + "234" 는 그냥 이으면 같은 문자열이 된다
        assertThat(CardFingerprint.of("34", "12"))
                .isNotEqualTo(CardFingerprint.of("234", "1"));
    }

    @Test
    @DisplayName("둘 다 없으면 지문이 없다 — 묶을 근거가 없는 것을 빈 문자열로 묶으면 안 된다")
    void noInputGivesNoKey() {
        assertThat(CardFingerprint.of(null, null)).isNull();
        assertThat(CardFingerprint.of("  ", null)).isNull();
        assertThat(CardFingerprint.of(null, "")).isNull();
    }

    @Test
    @DisplayName("한쪽만 있어도 지문을 낸다 — 발급사만 아는 것도 아무것도 모르는 것보다 낫다")
    void oneSideIsEnough() {
        assertThat(CardFingerprint.of(MASKED, null)).isNotNull();
        assertThat(CardFingerprint.of(null, "3K")).isNotNull();
    }
}
