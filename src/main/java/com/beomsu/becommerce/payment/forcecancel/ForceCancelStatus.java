package com.beomsu.becommerce.payment.forcecancel;

/**
 * 강제취소 요청의 상태 — maker-checker 흐름의 생명주기.
 *
 * <p>REQUESTED(요청됨) → EXECUTED(승인·실행됨) 또는 REJECTED(거부됨). 승인·거부는 요청자가 아닌
 * 다른 어드민(checker)이 수행하며, 승인 시에만 실제 결제 취소가 실행된다.
 */
public enum ForceCancelStatus {
    REQUESTED,
    EXECUTED,
    REJECTED
}
