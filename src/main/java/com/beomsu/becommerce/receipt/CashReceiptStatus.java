package com.beomsu.becommerce.receipt;

/** 현금영수증 발급 상태 — 비동기 발급이라 상태를 추적한다. */
public enum CashReceiptStatus {
    REQUESTED,  // 발급 요청됨(IN_PROGRESS)
    ISSUED,     // 발급 완료
    FAILED,     // 발급 실패
    CANCELED    // 취소됨(결제 취소 연쇄)
}
