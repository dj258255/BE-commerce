package com.beomsu.becommerce.fraud.review;

/** FDS 심사 큐 항목의 처리 상태. */
public enum FraudReviewStatus {
    PENDING,    // 심사 대기(사후탐지가 적재)
    APPROVED,   // 어드민 승인 — 정상 거래로 확인
    REJECTED    // 어드민 거부 — 부정 거래로 확인(카드 블랙리스트 등록)
}
