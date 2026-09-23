package com.beomsu.becommerce.reconciliation.internal;

/**
 * 대사 결과의 후속 처리 상태.
 *
 * <p>일치 건은 자동 종결, 나머지(불일치 3분류)는 사람 확인이 필요한 예외 큐로 남긴다.
 */
public enum ReconStatus {

    /** 자동 종결 — MATCHED */
    AUTO_RESOLVED,
    /** 사람 확인 필요 — INTERNAL_ONLY / EXTERNAL_ONLY / AMOUNT_MISMATCH */
    PENDING,
    /** 사람 확인 후 수기 확정 — 어드민이 PENDING 예외 큐를 검토해 종결 처리 */
    MANUALLY_RESOLVED
}
