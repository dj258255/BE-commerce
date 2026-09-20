package com.beomsu.becommerce.dispute.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 분쟁 도메인 예외. code는 API 에러 코드 체계와 일치한다. */
public class DisputeException extends DomainException {

    public DisputeException(String code, String message) {
        super(code, message);
    }

    public static DisputeException notFound(Long id) {
        return new DisputeException("DISPUTE_NOT_FOUND",
                "분쟁을 찾을 수 없습니다: " + id);
    }

    public static DisputeException invalidTransition(DisputeStatus from, DisputeStatus to) {
        return new DisputeException("INVALID_DISPUTE_TRANSITION",
                "허용되지 않은 분쟁 상태 전이입니다: %s → %s".formatted(from, to));
    }
}
