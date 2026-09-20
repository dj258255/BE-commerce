package com.beomsu.becommerce.payment.internal;

/**
 * 단건 결제 PG 강제 동기화 결과 뷰.
 *
 * <p>어드민이 특정 결제를 PG 조회로 즉시 확정한 뒤의 최신 상태를 반환한다. {@code status}는
 * 동기화 후 재조회한 결제의 상태 문자열이다.
 */
public record PaymentSyncView(Long paymentId, String orderNo, String status, String message) {
}
