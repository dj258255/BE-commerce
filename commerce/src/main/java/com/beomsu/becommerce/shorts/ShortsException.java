package com.beomsu.becommerce.shorts;

import com.beomsu.becommerce.shared.DomainException;

/** 숏폼 도메인 예외(R22). code는 10-API-스펙 문서의 에러 코드 체계에 맞춘다. */
public class ShortsException extends DomainException {

    public ShortsException(String code, String message) {
        super(code, message);
    }

    public static ShortsException invalidTransition(ShortVideoStatus from, ShortVideoStatus to) {
        return new ShortsException("INVALID_STATE_TRANSITION",
                "허용되지 않은 상태 전이입니다: %s → %s".formatted(from, to));
    }
}
