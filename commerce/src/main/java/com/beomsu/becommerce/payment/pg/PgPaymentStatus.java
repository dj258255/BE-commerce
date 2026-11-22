package com.beomsu.becommerce.payment.pg;

/** PG 측에서 본 결제 상태 — 복구 배치가 조회 API로 확인한다. */
public enum PgPaymentStatus {
    APPROVED,    // PG에는 승인으로 남아 있음 (우리가 타임아웃이었어도 실제론 됐던 것)
    NOT_FOUND,   // PG가 승인 실패를 확정함 (토스 ABORTED · EXPIRED, 가짜 PG 의 요청 미도달)
    /**
     * PG가 결제 기록을 돌려주지 않음(조회 404 또는 빈 응답). 승인 실패로 확정하지 않는다.
     *
     * <p>토스 조회 API 는 "승인된 결제를 paymentKey로 조회"한다고만 적고, 인증을 마친 결제가 404 가
     * 되는 경우를 정의하지 않는다. 승인 요청이 닿지 않았다면 결제는 IN_PROGRESS 로 보이다가
     * EXPIRED 가 되어야 한다. 그래서 404 는 반영 지연이나 잘못된 키일 수 있고, 바로 ABORTED 로
     * 닫으면 늦게 승인된 결제가 다음 날 대사에서야 드러난다. 복구는 결제 유효 시간이 지날 때까지
     * 다시 묻고 그 뒤에도 같으면 닫는다(app.recovery.missing-grace).
     */
    MISSING,
    CANCELED,    // 이미 취소됨
    IN_PROGRESS  // PG에서 아직 진행 중 — 승인도 실패도 확정되지 않았다(다음 주기에 다시 묻는다)
}
