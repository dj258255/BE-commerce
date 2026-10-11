package com.beomsu.becommerce.live;

import com.beomsu.becommerce.shared.DomainException;

/** 방송 중 "바로 주문" 도메인 예외(R10~R12). code는 10-API-스펙 문서의 에러 코드 체계에 맞춘다. */
public class LiveOrderException extends DomainException {

    public LiveOrderException(String code, String message) {
        super(code, message);
    }

    /**
     * R10.2: 요청한 productId가 지금 고정된 상품과 다르거나(화면이 바뀐 뒤 늦게 눌렀을 때),
     * 아예 고정된 상품이 없다(판매자가 방금 해제했을 때). {@link LivePinException#nothingPinned}와
     * 같은 코드("NOTHING_PINNED")를 쓴다 — 시청자 관점에서는 둘 다 "지금 보던 카드가 더 이상
     * 서버 상태와 맞지 않는다"는 같은 사건이고, 이미 409로 매핑돼 있다.
     */
    public static LiveOrderException pinMismatch(long broadcastId, long requestedProductId) {
        return new LiveOrderException("NOTHING_PINNED",
                "고정 상품이 요청과 다르거나 고정이 해제됐습니다: 방송 %d, 요청 상품 %d"
                        .formatted(broadcastId, requestedProductId));
    }

    /** R12: Redis 한정 수량 선점 실패 — 결제 호출 없이 즉시 거절한다(R15 방향). */
    public static LiveOrderException limitedQuantitySoldOut(long broadcastId, long productId) {
        return new LiveOrderException("LIMITED_QUANTITY_SOLD_OUT",
                "한정 수량이 모두 소진됐습니다: 방송 %d, 상품 %d".formatted(broadcastId, productId));
    }
}
