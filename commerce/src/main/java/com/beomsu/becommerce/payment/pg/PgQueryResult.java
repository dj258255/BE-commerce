package com.beomsu.becommerce.payment.pg;

/** PG 조회 결과. 복구 배치가 UNKNOWN 결제의 실제 상태를 확정하는 데 쓴다. */
public record PgQueryResult(PgPaymentStatus status, String method) {

    public boolean isApproved() {
        return status == PgPaymentStatus.APPROVED;
    }
}
