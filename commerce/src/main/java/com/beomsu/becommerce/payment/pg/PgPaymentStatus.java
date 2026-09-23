package com.beomsu.becommerce.payment.pg;

/** PG 측에서 본 결제 상태 — 복구 배치가 조회 API로 확인한다. */
public enum PgPaymentStatus {
    APPROVED,    // PG에는 승인으로 남아 있음 (우리가 타임아웃이었어도 실제론 됐던 것)
    NOT_FOUND,   // PG에 결제 정보 없음 (승인이 실제로 안 됐음)
    CANCELED,    // 이미 취소됨
    IN_PROGRESS  // PG에서 아직 진행 중 — 승인도 실패도 확정되지 않았다(다음 주기에 다시 묻는다)
}
