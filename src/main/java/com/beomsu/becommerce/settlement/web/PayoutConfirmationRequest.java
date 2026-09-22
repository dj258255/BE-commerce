package com.beomsu.becommerce.settlement.web;

/** 외부 지급 report 한 행을 정산 대사에 넣는 요청. */
public record PayoutConfirmationRequest(
        String payoutReference,
        String currency,
        long amount,
        boolean posted) {
}
