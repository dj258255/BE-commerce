package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.order.ProductCatalogFacts.ProductCardFacts;

import java.time.Instant;
import java.util.List;

/** 숏폼 영상 조회 뷰 — 업로드 완료 응답과 단건 조회 응답을 함께 쓴다. */
public record ShortVideoView(
        Long id,
        ShortVideoStatus status,
        Integer durationSeconds,
        Long fileSizeBytes,
        Integer width,
        Integer height,
        String failureReason,
        int retryCount,
        List<LinkedProduct> products,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * {@code productCards}는 호출자({@code ShortsService})가 {@code ProductCatalogFacts}로 읽어
     * 넘긴 연결 상품 카드다 — 카탈로그에서 사라진 상품은 이미 그 단계에서 빠져 있다(R25).
     */
    public static ShortVideoView from(ShortVideo v, List<ProductCardFacts> productCards) {
        List<LinkedProduct> products = productCards.stream()
                .map(c -> new LinkedProduct(c.productId(), c.name(), c.price()))
                .toList();
        return new ShortVideoView(v.getId(), v.getStatus(), v.getDurationSeconds(), v.getFileSizeBytes(),
                v.getWidth(), v.getHeight(), v.getFailureReason(), v.getRetryCount(),
                products, v.getCreatedAt(), v.getUpdatedAt());
    }

    /** 연결 상품 카드 — 시청자가 영상에서 상품 페이지로 가는 데 필요한 최소 값(R25). */
    public record LinkedProduct(long productId, String name, long price) {
    }
}
