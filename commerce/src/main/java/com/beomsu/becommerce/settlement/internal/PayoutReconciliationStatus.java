package com.beomsu.becommerce.settlement.internal;

/** 지급 대사 결과. PENDING은 내부 예외 큐의 미해결과 구별되는 외부 지급의 잠정 상태다. */
public enum PayoutReconciliationStatus {
    MATCHED,
    PENDING,
    AMOUNT_MISMATCH,
    CURRENCY_MISMATCH,
    UNMATCHED_SETTLEMENT,
    UNMATCHED_PAYOUT,
    DUPLICATE_PAYOUT_REFERENCE
}
