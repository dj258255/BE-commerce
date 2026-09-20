package com.beomsu.becommerce.assist.fraudreview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FraudDraftReviewRepository extends JpaRepository<FraudDraftReview, Long> {

    /**
     * 그 사람이 그 건을 이미 열었는지.
     *
     * <p>유니크 제약이 마지막에 막지만, 여기서 먼저 보고 있던 행을 돌려줘야 <b>다시 열었을 때
     * 같은 초안·같은 순서</b>가 나온다. 새로 만들면 모델이 다르게 쓴 문장이 뜨고, 그러면
     * 채점이 앞서 본 화면과 안 맞는다.
     */
    Optional<FraudDraftReview> findByFraudReviewIdAndReviewer(long fraudReviewId, String reviewer);
}
