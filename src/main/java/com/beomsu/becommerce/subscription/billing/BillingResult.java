package com.beomsu.becommerce.subscription.billing;

/**
 * 빌링키 청구 결과 — dunning 분기의 근거.
 *
 * <p>결제 실패를 두 부류로 나누는 것이 dunning의 핵심이다(Stripe/Recurly 벤치마크). 재시도가
 * 유효한 실패({@link #SOFT_DECLINE})와, 재시도하면 오히려 가맹점 평판을 깎는 실패
 * ({@link #HARD_DECLINE})를 구분한다.
 */
public enum BillingResult {

    /** 청구 성공 */
    SUCCESS,

    /** 잔액부족·한도초과·일시 오류 등 — 재시도가 유효한 실패. 유예기간 두고 재청구한다. */
    SOFT_DECLINE,

    /**
     * 도난·분실·무효 카드 등 — 재시도가 무의미하고 유해한 실패.
     * 반복 시도하면 카드사에서 가맹점 평판 점수가 하락하므로 재시도 금지, 즉시 정지 후 카드 변경 요청.
     */
    HARD_DECLINE
}
