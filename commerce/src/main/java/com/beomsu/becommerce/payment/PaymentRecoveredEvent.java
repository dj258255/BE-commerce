package com.beomsu.becommerce.payment;

/**
 * 결과를 모르던 결제를 결제 복구가 PG 조회로 확정했다(#378). 승인 · PG 에 없음 · PG 에서 취소 모두 낸다.
 *
 * <p>주문이 이 이벤트를 듣고 멈춰 있는 주문을 바로 마무리한다(재고 차감 · PAID, 또는 PENDING_PAYMENT 복귀).
 * 예전에는 승인만 {@link PaymentConfirmedEvent} 를 냈고 주문은 그것도 듣지 않아, 주문 복구 배치가
 * 10분 넘게 멈춘 주문을 볼 때까지 마무리가 미뤄졌다(한정 상품 부하에서 중앙값 약 640초, #374).
 *
 * <p>정상 결제에서는 내지 않는다. 식별자와 확정된 상태만 담는다.
 */
public record PaymentRecoveredEvent(String orderNo, Long paymentId, PaymentStatus status) {
}
