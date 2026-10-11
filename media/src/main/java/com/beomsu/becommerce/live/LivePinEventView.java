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

    /**
     * @param remainingQuantity 지금 실제로 남은 수량(R13·R14) — {@code pin.getLimitedQuantity()}가
     *                          아니라 호출자가 {@code LiveOrderGate.currentCount}로 구한
     *                          "한도 − 지금 선점 수"다. 드롭을 막 걸었을 때(선점 0건)는 둘이
     *                          같지만, 판매 중간에 가격을 바꾸거나 시청자가 재연결할 때는 달라야
     *                          한다 — 그렇지 않으면 이미 몇 개 팔린 뒤에도 "남은 수량"이 원래
     *                          한도 그대로 보인다(버그, ADR-085 R13·R14 절 참고).
     */
    static LivePinEventView pinned(long broadcastId, LivePin pin, String productName, int remainingQuantity) {
        return new LivePinEventView(broadcastId, LivePinEventType.PINNED, pin.getProductId(), productName,
                pin.getPrice(), remainingQuantity, pin.getSeq(), pin.getEffectiveAt());
    }

    static LivePinEventView unpinned(long broadcastId, long seq, Instant effectiveAt) {
        return new LivePinEventView(broadcastId, LivePinEventType.UNPINNED, null, null, null, null, seq, effectiveAt);
    }

    /** {@code remainingQuantity}의 의미는 {@link #pinned}와 같다 — 가격만 바뀌어도 실제 남은 수량을 다시 구해 싣는다. */
    static LivePinEventView priceChanged(long broadcastId, LivePin pin, String productName, int remainingQuantity) {
        return new LivePinEventView(broadcastId, LivePinEventType.PRICE_CHANGED, pin.getProductId(), productName,
                pin.getPrice(), remainingQuantity, pin.getSeq(), pin.getEffectiveAt());
    }

    /**
     * 한정 수량 선점·반환에 따른 남은 수량 갱신(R13·R14, ADR-085) — 받는 즉시 적용해야 한다
     * ({@link LivePinEventType#QUANTITY_CHANGED} 참고, effectiveAt 게이트를 타지 않는다).
     * 상품 이름·가격은 다시 보내지 않는다(PINNED 스냅샷에서 이미 안다 — 매 주문마다 카탈로그
     * 조회를 반복하지 않으려는 것이다).
     */
    static LivePinEventView quantityChanged(long broadcastId, long productId, long seq, int remainingQuantity,
            Instant now) {
        return new LivePinEventView(broadcastId, LivePinEventType.QUANTITY_CHANGED, productId, null, null,
                remainingQuantity, seq, now);
    }
}
