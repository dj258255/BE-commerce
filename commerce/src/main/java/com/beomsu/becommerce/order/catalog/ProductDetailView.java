package com.beomsu.becommerce.order.catalog;

import java.util.List;

/**
 * 상품 상세 — 목록 카드와 달리 <b>리뷰</b>가 함께 나간다(#168). 요약에 설명을 더한다.
 *
 * <p><b>{@code reviewsSynthetic} 이 있는 이유</b>: 이 리뷰들은 실데이터가 아니다(코퍼스에 리뷰가 없다).
 * 화면이 그 사실을 밝히려면 API 가 먼저 밝혀야 한다 — 프론트가 추측하게 두면 언젠가 배지를 잊는다.
 * 그래서 <b>값이 아니라 사실을 내보낸다</b>: 프론트는 문구를 고르는 일만 한다.
 *
 * <p><b>평균 평점 필드는 없다.</b> 넣으면 목록·정렬·화면이 그 값을 품질 신호로 쓰기 시작한다.
 * 리뷰는 <b>읽는 것</b>이지 <b>재는 것</b>이 아니다 — 그 결정이 이 record 의 모양이다(ADR-046).
 */
public record ProductDetailView(long productId, String name, long price, String brand,
                                String categoryCode, String categoryName, String description,
                                String imageUrl, boolean inStock,
                                List<ReviewView> reviews, boolean reviewsSynthetic) {

    /** 리뷰 한 건. {@code source} 를 그대로 내보내 화면이 배지 문구의 근거로 쓴다. */
    public record ReviewView(String source, int rating, String body, String author, String createdAt) {
    }

    static ProductDetailView of(Product p, String categoryName, boolean inStock,
                                List<ReviewView> reviews) {
        return new ProductDetailView(p.getProductId(), p.getName(), p.getPrice(), p.getBrand(),
                p.getCategoryCode(), categoryName, p.getDescription(), p.getImageUrl(), inStock,
                reviews, true);
    }
}
