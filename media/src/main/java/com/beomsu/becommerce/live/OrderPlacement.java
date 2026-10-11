package com.beomsu.becommerce.live;

import java.time.Instant;

/**
 * media(live)가 commerce 주문 생성을 부르기 위한 포트(R10·R11, R32·ADR-085) — {@link ProductLookup}과
 * 같은 모양(인터페이스는 media에 두고, 구현(어댑터)은 commerce 쪽 분리 패키지에 둔다). 주문
 * 생성·결제·재고 확정 로직은 이 모듈에 전혀 두지 않는다 — 이 포트는 "이미 서버 상태로 검증된
 * 가격으로 주문을 만들어 달라"고 commerce의 기존 주문 흐름(CheckoutService)에 그대로 위임할
 * 뿐이다.
 */
public interface OrderPlacement {

    /**
     * 방송 특가 주문 하나(수량 1, ADR-085 범위)를 만든다.
     *
     * @param unitPrice      호출자(media)가 지금 고정된 방송 특가로 이미 확인한 가격(R10) —
     *                       commerce는 이 값 그대로 주문 금액을 계산한다. 상품 실존 확인과
     *                       카탈로그 재고 보호(StockReservationService)는 commerce가 그대로 한다
     * @param idempotencyKey 같은 키로 다시 불러도 주문이 두 번 생기지 않는다(R12.2)
     */
    PlacedOrder place(long userId, long productId, long unitPrice, String idempotencyKey);

    record PlacedOrder(String orderNo, long totalAmount, Instant expiresAt) {
    }
}
