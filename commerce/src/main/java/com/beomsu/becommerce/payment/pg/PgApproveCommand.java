package com.beomsu.becommerce.payment.pg;

/**
 * PG 승인 요청.
 *
 * @param installmentMonths 할부 개월. <b>0이면 일시불</b>. 카드사에 그대로 전달되며,
 *                          우리가 받을 정산 금액은 이 값과 무관하다(카드사가 일시에 지급한다)
 * @param idempotencyKey    PG에 보내는 {@code Idempotency-Key}. 토스는 이 키가 15일 유효하고 같은 키로
 *                          다시 요청하면 첫 응답을 그대로 돌려준다(토스 문서, using-api/idempotency-key).
 *                          그래서 <b>결제 시도(paymentId)마다</b> 달라야 한다 — 주문번호만 쓰면 거절 뒤
 *                          같은 주문으로 재시도했을 때 이전 거절 응답이 그대로 돌아올 수 있다(#402).
 *                          {@link ResilientPgClient} 의 재전송은 같은 커맨드를 그대로 재사용하므로
 *                          같은 시도 안에서는 이 키가 유지된다(#395).
 */
public record PgApproveCommand(String paymentKey, String orderNo, long amount, int installmentMonths,
                                String idempotencyKey) {

    /** 일시불 + 시도별 키 없이 주문번호만 쓴다(옛 동작, 테스트 편의). */
    public PgApproveCommand(String paymentKey, String orderNo, long amount) {
        this(paymentKey, orderNo, amount, 0, orderNo);
    }

    /** 할부 + 시도별 키 없이 주문번호만 쓴다(옛 동작, 테스트 편의). */
    public PgApproveCommand(String paymentKey, String orderNo, long amount, int installmentMonths) {
        this(paymentKey, orderNo, amount, installmentMonths, orderNo);
    }
}
