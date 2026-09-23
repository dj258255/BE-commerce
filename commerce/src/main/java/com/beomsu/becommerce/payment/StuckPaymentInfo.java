package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.order.internal.CheckoutTx;
import com.beomsu.becommerce.payment.ApprovalOutcome;

/**
 * 멈춘 사가 복구용 — 특정 주문의 카드 결제 상태를 PG 조회로 확정한 뒤, order 모듈에 노출하는 정보.
 * order의 체크아웃 복구가 이 값으로 {@code CheckoutTx.settle}을 재실행해 주문을 완결/롤백한다.
 *
 * @param paymentId 결제 id
 * @param amount    카드 결제 금액(정산 대상). 포인트분은 order.totalAmount − amount로 복구 측이 도출.
 * @param outcome   PG 조회로 확정된 결과(SUCCESS/FAILED)를 {@link ApprovalOutcome}로 매핑한 값
 */
public record StuckPaymentInfo(Long paymentId, long amount, ApprovalOutcome outcome) {
}
