package com.beomsu.becommerce.escrow.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 에스크로 도메인 예외. code는 API 에러 응답의 code 필드로 노출된다. */
public class EscrowException extends DomainException {

    public EscrowException(String code, String message) {
        super(code, message);
    }

    public static EscrowException notFound(String orderNo) {
        return new EscrowException("ESCROW_NOT_FOUND", "에스크로 홀드를 찾을 수 없습니다: " + orderNo);
    }

    public static EscrowException invalidState(String message) {
        return new EscrowException("INVALID_ESCROW_STATE", message);
    }
}
