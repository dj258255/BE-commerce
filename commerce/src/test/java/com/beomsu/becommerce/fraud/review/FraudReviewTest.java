package com.beomsu.becommerce.fraud.review;

import com.beomsu.becommerce.fraud.internal.FraudResult;
import com.beomsu.becommerce.fraud.internal.FraudException;
import com.beomsu.becommerce.fraud.internal.FdsDecision;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FraudReviewTest {

    private FraudReview flaggedReview() {
        return FraudReview.flagged("ord-1", 10L, "card-xyz", 50_000,
                new FraudResult(70, FdsDecision.REVIEW, List.of("HIGH_AMOUNT")));
    }

    @Test
    @DisplayName("flagged: PENDING 상태 + FraudResult에서 점수·판정·근거 매핑")
    void flaggedMapsResult() {
        FraudReview review = flaggedReview();

        assertThat(review.getStatus()).isEqualTo(FraudReviewStatus.PENDING);
        assertThat(review.getScore()).isEqualTo(70);
        assertThat(review.getDecision()).isEqualTo(FdsDecision.REVIEW);
        assertThat(review.getReasons()).isEqualTo("HIGH_AMOUNT");
        assertThat(review.getCreatedAt()).isNotNull();
        assertThat(review.getReviewedAt()).isNull();
    }

    @Test
    @DisplayName("flagged: 근거가 500자를 넘으면 방어 절단")
    void flaggedTruncatesReasons() {
        String longReason = "R".repeat(600);
        FraudReview review = FraudReview.flagged("ord-1", 10L, "card-xyz", 50_000,
                new FraudResult(70, FdsDecision.REVIEW, List.of(longReason)));

        assertThat(review.getReasons()).hasSize(500);
    }

    @Test
    @DisplayName("approve: PENDING → APPROVED + reviewedBy/reviewedAt 기록")
    void approveTransitions() {
        FraudReview review = flaggedReview();

        review.approve("admin");

        assertThat(review.getStatus()).isEqualTo(FraudReviewStatus.APPROVED);
        assertThat(review.getReviewedBy()).isEqualTo("admin");
        assertThat(review.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("reject: PENDING → REJECTED + reviewedBy/reviewedAt 기록")
    void rejectTransitions() {
        FraudReview review = flaggedReview();

        review.reject("admin");

        assertThat(review.getStatus()).isEqualTo(FraudReviewStatus.REJECTED);
        assertThat(review.getReviewedBy()).isEqualTo("admin");
        assertThat(review.getReviewedAt()).isNotNull();
    }

    @Test
    @DisplayName("approve 가드: 이미 종결된 항목이면 INVALID_FRAUD_REVIEW_STATE")
    void approveGuardsNonPending() {
        FraudReview review = flaggedReview();
        review.approve("admin");

        assertThatThrownBy(() -> review.approve("admin2"))
                .isInstanceOf(FraudException.class)
                .satisfies(e -> assertThat(((FraudException) e).code())
                        .isEqualTo("INVALID_FRAUD_REVIEW_STATE"));
    }

    @Test
    @DisplayName("reject 가드: 이미 종결된 항목이면 INVALID_FRAUD_REVIEW_STATE")
    void rejectGuardsNonPending() {
        FraudReview review = flaggedReview();
        review.reject("admin");

        assertThatThrownBy(() -> review.reject("admin2"))
                .isInstanceOf(FraudException.class)
                .satisfies(e -> assertThat(((FraudException) e).code())
                        .isEqualTo("INVALID_FRAUD_REVIEW_STATE"));
    }
}
