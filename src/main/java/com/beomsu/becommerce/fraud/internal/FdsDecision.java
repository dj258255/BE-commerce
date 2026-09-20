package com.beomsu.becommerce.fraud.internal;

/** FDS 판정 — 위험 점수 구간별 대응. */
public enum FdsDecision {
    ALLOW,      // 통과
    CHALLENGE,  // 추가 인증 요구
    REVIEW,     // 사후 사람 검토 큐
    BLOCK       // 차단
}
