package com.beomsu.becommerce.reconciliation.internal;

/**
 * 대사 결과 4분류.
 *
 * <p>내부 기록(결제 기대치)과 외부 기록(PG 정산 파일)을 orderNo로 매칭한 판정.
 */
public enum ReconResultType {

    /** 양쪽에 있고 금액도 같음 — 정상 */
    MATCHED,
    /** 내부에만 있음 — PG 정산 파일 누락 의심 */
    INTERNAL_ONLY,
    /** 외부에만 있음 — 내부 기록 유실 의심 */
    EXTERNAL_ONLY,
    /** 양쪽에 있으나 금액이 다름 — 위변조/부분취소 미반영 의심 */
    AMOUNT_MISMATCH
}
