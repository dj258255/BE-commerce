package com.beomsu.becommerce.dispute.internal;

/**
 * 분쟁 상태. OPEN(개시) → EVIDENCE_SUBMITTED(증빙 제출) → WON/LOST(최종). 전이 규칙은
 * {@link Dispute}가 강제한다(허용되지 않은 전이는 {@link DisputeException#invalidTransition}).
 */
public enum DisputeStatus {
    OPEN,
    EVIDENCE_SUBMITTED,
    WON,
    LOST;

    /** 최종 상태(더 이상 전이 불가) 여부. */
    public boolean isTerminal() {
        return this == WON || this == LOST;
    }
}
