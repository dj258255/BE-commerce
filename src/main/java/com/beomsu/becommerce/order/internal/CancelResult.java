package com.beomsu.becommerce.order.internal;

/**
 * 주문 취소 결과 — 환불 배분(포인트/월렛/카드 몫)과 전액 취소 여부를 담는다.
 *
 * @param orderNo        취소한 주문 번호
 * @param orderStatus    취소 후 주문 상태(전액 취소면 CANCELED, 부분 취소면 PAID 유지)
 * @param refundedPoint  포인트로 환불된 금액
 * @param refundedWallet 월렛으로 환불된 금액
 * @param refundedCard   카드로 취소된 금액
 * @param fullyCanceled  전액 취소 여부
 */
public record CancelResult(String orderNo, OrderStatus orderStatus,
                           long refundedPoint, long refundedWallet, long refundedCard, boolean fullyCanceled) {
}
