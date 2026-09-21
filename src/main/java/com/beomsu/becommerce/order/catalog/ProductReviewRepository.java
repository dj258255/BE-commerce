package com.beomsu.becommerce.order.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface ProductReviewRepository extends JpaRepository<ProductReview, Long> {

    /**
     * 상품의 리뷰 — 최근 것부터.
     *
     * <p><b>집계 질의는 두지 않는다.</b> 평균·개수 같은 값을 여기서 만들면 그 값이 상품의 품질 신호로
     * 쓰이고, 화면이 합성 리뷰를 실제 평점처럼 보여주게 된다(#168, ADR-046). 목록 조회만 있다.
     */
    List<ProductReview> findByProductIdOrderByIdDesc(long productId, Pageable pageable);
}
