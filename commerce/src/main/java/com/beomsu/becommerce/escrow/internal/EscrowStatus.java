package com.beomsu.becommerce.escrow.internal;

/**
 * 에스크로 홀드 상태.
 *
 * <p>HELD(보류) → RELEASED(구매확정, 정산 가능) 또는 HELD → REFUNDED(취소, 판매자 미정산).
 * RELEASED·REFUNDED는 종결 상태다.
 */
public enum EscrowStatus {

    /** 보류 — 결제금이 잡혀 있고 아직 정산되지 않은 상태 */
    HELD,
    /** 구매확정 — 정산 파이프라인으로 릴리스된 종결 상태 */
    RELEASED,
    /** 환불 — 구매확정 전 취소되어 판매자에게 정산되지 않은 종결 상태 */
    REFUNDED
}
