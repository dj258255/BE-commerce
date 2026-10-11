package com.beomsu.becommerce.shorts;

import java.time.Instant;
import java.util.List;

/**
 * 숏폼 피드 항목(R26) — 시청자(비로그인 포함)에게 보이는 공개 뷰다.
 *
 * <p>{@code sellerId}·{@code objectKey}·{@code failureReason}·{@code retryCount} 같은 운영·소유권
 * 필드는 담지 않는다({@code ShortVideoView}와 다른 점) — 피드는 READY만 보여주므로 실패 사유가
 * 있을 수 없고, 소유자 전용 정보를 공개 엔드포인트에 실을 이유가 없다.
 *
 * <p>{@code masterPlaylistUrl}·{@code thumbnailUrl}(R26 재생)은 {@code GET
 * /api/v1/shorts/{id}/media/**}(공개, {@code ShortsMediaController})로 바로 재생·표시할 수 있는
 * 상대 경로다 — READY만 피드에 나오므로 이 두 값은 항상 채워져 있다(R23.2가 다섯 산출물이 전부
 * 있어야 READY를 보장한다).
 */
public record ShortsFeedItemView(
        long id,
        int durationSeconds,
        int width,
        int height,
        Instant createdAt,
        String masterPlaylistUrl,
        String thumbnailUrl,
        List<ShortVideoView.LinkedProduct> products) {

    public static ShortsFeedItemView from(ShortVideo v, List<ProductLookup.Product> products) {
        List<ShortVideoView.LinkedProduct> linked = products.stream()
                .map(p -> new ShortVideoView.LinkedProduct(p.productId(), p.name(), p.price()))
                .toList();
        return new ShortsFeedItemView(v.getId(), v.getDurationSeconds(), v.getWidth(), v.getHeight(),
                v.getCreatedAt(),
                ShortsMediaUrls.of(v.getId(), v.getMasterPlaylistPath()),
                ShortsMediaUrls.of(v.getId(), v.getThumbnailPath()),
                linked);
    }
}
