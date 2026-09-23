package com.beomsu.becommerce.payment.pg;

/** PG 승인 요청. 멱등키는 Phase 2에서 추가된다. */
/**
 * PG 승인 요청.
 *
 * @param installmentMonths 할부 개월. <b>0이면 일시불</b>. 카드사에 그대로 전달되며,
 *                          우리가 받을 정산 금액은 이 값과 무관하다(카드사가 일시에 지급한다)
 */
public record PgApproveCommand(String paymentKey, String orderNo, long amount, int installmentMonths) {

    /** 일시불. */
    public PgApproveCommand(String paymentKey, String orderNo, long amount) {
        this(paymentKey, orderNo, amount, 0);
    }
}
