package com.beomsu.becommerce.live;

import com.beomsu.becommerce.shared.DomainException;

/** 상품 고정 도메인 예외(R8). code는 10-API-스펙 문서의 에러 코드 체계에 맞춘다. */
public class LivePinException extends DomainException {

    public LivePinException(String code, String message) {
        super(code, message);
    }

    /** R8: 해제·가격 변경을 시도했는데 지금 고정된 상품이 없음 — 409(현재 상태와 충돌). */
    public static LivePinException nothingPinned(long broadcastId) {
        return new LivePinException("NOTHING_PINNED", "고정된 상품이 없습니다: 방송 " + broadcastId);
    }

    /** R25의 같은 상황과 같은 코드(카탈로그에 없는 상품) — {@code ShortsException.productNotFound} 참고. */
    public static LivePinException productNotFound(long productId) {
        return new LivePinException("PRODUCT_NOT_FOUND", "상품을 찾을 수 없습니다: " + productId);
    }

    /** R8: "방송 중" 고정만 허용한다(EARS 문구) — SCHEDULED·ENDED에서는 거절한다. */
    public static LivePinException broadcastNotLive(long broadcastId, LiveBroadcastStatus status) {
        return new LivePinException("BROADCAST_NOT_LIVE",
                "방송 중(LIVE)일 때만 상품을 고정할 수 있습니다: 방송 %d 상태 %s".formatted(broadcastId, status));
    }

    public static LivePinException invalidPrice(long price) {
        return new LivePinException("INVALID_PRICE", "방송 특가는 0보다 커야 합니다: " + price);
    }

    public static LivePinException invalidQuantity(int quantity) {
        return new LivePinException("INVALID_QUANTITY", "한정 수량은 0보다 커야 합니다: " + quantity);
    }
}
