package com.beomsu.becommerce.payment.pg;

/** PG 취소 결과. transactionKey는 취소 건마다 PG가 발급한다. */
public record PgCancelResult(String transactionKey) {
}
