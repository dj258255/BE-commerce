package com.beomsu.becommerce.assist.review;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

interface BlindReviewRepository extends JpaRepository<BlindReview, Long> {

    /** 같은 사람이 같은 건을 두 번 리뷰하면 두 번째는 이미 답을 아는 상태다. */
    Optional<BlindReview> findByReconResultIdAndReviewer(Long reconResultId, String reviewer);

    /** 집계용 — 3단계까지 끝난 표본만. */
    List<BlindReview> findByEditedAtIsNotNull();
}
