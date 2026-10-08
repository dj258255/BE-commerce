package com.beomsu.becommerce.live;

import java.time.Instant;

/**
 * 고정 이벤트(R9) — WebSocket으로 시청자에게 그대로 JSON 직렬화해 보내는 메시지이자, 재연결
 * 시(R9.2) 돌려주는 스냅샷과 같은 모양이다. {@code type}이 {@code UNPINNED}면 상품 관련
 * 필드는 전부 null이다.
 *
 * <p>{@code seq}(단조 증가)와 {@code effectiveAt}(서버 시각)이 R9의 동기화 축이다 — 클라이언트는
 * 재생 시점이 {@code effectiveAt}에 도달한 뒤에만 카드를 바꾸고(R9.1), {@code seq}가 이미
 * 처리한 값보다 작으면 통째로 무시한다(R9.3). 스냅샷(R9.2)도 같은 모양을 재사용한다 —
 * {@code effectiveAt}이 이미 지난 시각이라 받는 즉시 적용 조건을 만족한다(별도 "스냅샷"
 * 타입이 필요 없다).
 */
public record LivePinEventView(
        long broadcastId,
        LivePinEventType type,
        Long productId,
        String productName,
        Long price,
        Integer remainingQuantity,
        long seq,
        Instant effectiveAt) {

    static LivePinEventView pinned(long broadcastId, LivePin pin, String productName) {
        return new LivePinEventView(broadcastId, LivePinEventType.PINNED, pin.getProductId(), productName,
                pin.getPrice(), pin.getLimitedQuantity(), pin.getSeq(), pin.getEffectiveAt());
    }

    static LivePinEventView unpinned(long broadcastId, long seq, Instant effectiveAt) {
        return new LivePinEventView(broadcastId, LivePinEventType.UNPINNED, null, null, null, null, seq, effectiveAt);
    }

    static LivePinEventView priceChanged(long broadcastId, LivePin pin, String productName) {
        return new LivePinEventView(broadcastId, LivePinEventType.PRICE_CHANGED, pin.getProductId(), productName,
                pin.getPrice(), pin.getLimitedQuantity(), pin.getSeq(), pin.getEffectiveAt());
    }
}
