package com.beomsu.becommerce.payment.pg;

/**
 * PG 호출 결과의 3-상태 모델 (카카오페이 "MSA 네트워크 예외" 방식).
 * 타임아웃은 실패가 아니라 TIMEOUT(=Unknown) — 상대 서버에서 처리됐을 수도 있다.
 */
public enum PgOutcome {
    SUCCESS,
    FAILED,   // 명시적 거절 (잔액부족·카드거절 등)
    TIMEOUT   // 응답 없음 — 미확정
}
