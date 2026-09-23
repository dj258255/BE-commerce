package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 정산 도메인 예외. code는 10-API-스펙 문서의 에러 코드 체계와 일치한다. */
public class SettlementException extends DomainException {

    public SettlementException(String code, String message) {
        super(code, message);
    }

    /** 지급 확정 대상 정산을 찾지 못한 경우 — 404. */
    static SettlementException notFound(long settlementId) {
        return new SettlementException("SETTLEMENT_NOT_FOUND",
                "정산을 찾을 수 없습니다: " + settlementId);
    }

    static SettlementException payoutReconciliationRequired(long settlementId) {
        return new SettlementException("PAYOUT_RECONCILIATION_REQUIRED",
                "외부 지급 report가 MATCHED가 아니어서 지급을 확정할 수 없습니다: " + settlementId);
    }
}
