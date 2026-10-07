package com.beomsu.becommerce.shorts;

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
     * {@code products}는 호출자({@code ShortsService})가 {@link ProductLookup}으로 읽어 넘긴
     * 연결 상품 카드다 — 카탈로그에서 사라진 상품은 이미 그 단계에서 빠져 있다(R25).
     */
    public static ShortVideoView from(ShortVideo v, List<ProductLookup.Product> products) {
        List<LinkedProduct> linked = products.stream()
                .map(p -> new LinkedProduct(p.productId(), p.name(), p.price()))
                .toList();
        return new ShortVideoView(v.getId(), v.getStatus(), v.getDurationSeconds(), v.getFileSizeBytes(),
                v.getWidth(), v.getHeight(), v.getFailureReason(), v.getRetryCount(),
                linked, v.getCreatedAt(), v.getUpdatedAt());
    }

    /** 연결 상품 카드 — 시청자가 영상에서 상품 페이지로 가는 데 필요한 최소 값(R25). */
    public record LinkedProduct(long productId, String name, long price) {
    }
}
