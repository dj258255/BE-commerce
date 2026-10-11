package com.beomsu.becommerce.live.web;

/**
 * 고정 상품 카드 "바로 주문" 요청(R10·R11) — productId만 받는다. 가격·수량은 서버가 지금
 * 고정 상태로 판정한다(R10: 클라이언트가 보낸 값으로 판정하지 않는다).
 */
public record LiveOrderRequest(long productId) {
}
