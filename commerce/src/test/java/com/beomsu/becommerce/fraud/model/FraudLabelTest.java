package com.beomsu.becommerce.fraud.model;

import com.beomsu.becommerce.dispute.DisputeOutcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 차지백에서 라벨을 뽑는 규칙을 고정한다.
 *
 * <p><b>여기가 틀리면 그 뒤가 전부 틀린다.</b> 부정 차지백을 정상으로 세면 재현율이 부풀고,
 * 반대면 오탐률이 부푼다. 그리고 이 값은 모델을 켤지 정하는 근거로 들어간다.
 */
@DisplayName("이상거래 라벨 — 차지백 결과에서 뽑는다")
class FraudLabelTest {

    private static DisputeOutcome outcome(String reason, String status) {
        return new DisputeOutcome("ORD-1", "CB-1", reason, status,
                "OPEN".equals(status) ? null : Instant.now());
    }

    @ParameterizedTest(name = "{0} 은 부정 사유다")
    @ValueSource(strings = {
            "10.4", "Visa 10.4 Fraud Card-Absent Environment",
            "4837", "MASTERCARD 4837 No Cardholder Authorization",
            "FRAUD", "unauthorized transaction", "카드 도용", "미승인 거래"})
    void fraudReasonCodesAreRecognised(String reason) {
        assertThat(FraudLabel.isFraudReason(reason)).isTrue();
    }

    @ParameterizedTest(name = "{0} 은 부정 사유가 아니다")
    @ValueSource(strings = {
            "13.1 Merchandise Not Received", "duplicate processing",
            "상품 미도착", "중복 청구", "13.3 Not as Described"})
    void nonFraudReasonCodesAreNotFraud(String reason) {
        assertThat(FraudLabel.isFraudReason(reason)).isFalse();
    }

    @Test
    @DisplayName("부정 사유로 졌으면 부정이다")
    void lostFraudDisputeIsFraud() {
        assertThat(FraudLabel.of(outcome("10.4", "LOST"))).isEqualTo(FraudLabel.FRAUD);
    }

    @Test
    @DisplayName("부정 사유인데 이겼으면 정상이다 — 발급사가 우리 손을 들어 줬다")
    void wonFraudDisputeIsLegitimate() {
        assertThat(FraudLabel.of(outcome("10.4", "WON"))).isEqualTo(FraudLabel.LEGITIMATE);
    }

    @Test
    @DisplayName("상품 미도착으로 졌어도 부정거래 판정의 정답은 아니다")
    void lostNonFraudDisputeIsLegitimate() {
        assertThat(FraudLabel.of(outcome("13.1 Merchandise Not Received", "LOST")))
                .isEqualTo(FraudLabel.LEGITIMATE);
    }

    @Test
    @DisplayName("진행 중이면 모른다 — 밀릴수록 라벨이 좋아 보이면 안 된다")
    void openDisputeIsUnknown() {
        assertThat(FraudLabel.of(outcome("10.4", "OPEN"))).isEqualTo(FraudLabel.UNKNOWN);
        assertThat(FraudLabel.of(outcome("10.4", "EVIDENCE_SUBMITTED"))).isEqualTo(FraudLabel.UNKNOWN);
    }

    @Test
    @DisplayName("분쟁이 없으면 모른다")
    void noDisputeIsUnknown() {
        assertThat(FraudLabel.of(null)).isEqualTo(FraudLabel.UNKNOWN);
    }

    @Test
    @DisplayName("사유가 비어 있으면 부정으로 안 본다 — 근거 없이 부정으로 세면 오탐률이 부푼다")
    void blankReasonIsNotFraud() {
        assertThat(FraudLabel.of(outcome(null, "LOST"))).isEqualTo(FraudLabel.LEGITIMATE);
        assertThat(FraudLabel.of(outcome("  ", "LOST"))).isEqualTo(FraudLabel.LEGITIMATE);
    }
}
