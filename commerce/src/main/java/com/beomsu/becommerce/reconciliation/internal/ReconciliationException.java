package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 대사 도메인 예외. code는 10-API-스펙 문서의 에러 코드 체계와 일치한다. */
public class ReconciliationException extends DomainException {

    public ReconciliationException(String code, String message) {
        super(code, message);
    }

    /** PENDING(사람 확인 대기)이 아닌 대사 결과를 수기 확정하려 한 경우 — 상태 충돌(409). */
    static ReconciliationException notPending(ReconStatus current) {
        return new ReconciliationException("INVALID_STATE_TRANSITION",
                "PENDING(사람 확인 대기) 상태만 수기 확정할 수 있습니다: 현재 " + current);
    }
}
