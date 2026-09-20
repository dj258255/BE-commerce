package com.beomsu.becommerce.order.internal;

/**
 * 구매확정 결과.
 *
 * @param orderNo 구매확정한 주문 번호
 * @param message 처리 결과 메시지
 */
public record PurchaseConfirmationResult(String orderNo, String message) {
}
