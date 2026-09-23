package com.beomsu.becommerce.fraud.internal;

import com.beomsu.becommerce.shared.DomainException;

/** FDS 도메인 예외. code는 API 에러 응답의 code 필드로 노출된다. */
public class FraudException extends DomainException {

    public FraudException(String code, String message) {
        super(code, message);
    }

    public static FraudException notFound(long id) {
        return new FraudException("FRAUD_REVIEW_NOT_FOUND", "심사 항목을 찾을 수 없습니다: " + id);
    }

    public static FraudException invalidState(String message) {
        return new FraudException("INVALID_FRAUD_REVIEW_STATE", message);
    }
}
