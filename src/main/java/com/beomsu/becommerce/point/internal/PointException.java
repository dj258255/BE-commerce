package com.beomsu.becommerce.point.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 포인트 도메인 예외. code는 API 에러 응답의 code 필드로 그대로 노출된다. */
public class PointException extends DomainException {

    public PointException(String code, String message) {
        super(code, message);
    }

    /** 포인트 잔액 부족 — 값 타입/엔티티 수준에서 마이너스 잔액을 차단한다. */
    public static PointException insufficient(long balance, long requested) {
        return new PointException("INSUFFICIENT_POINT",
                "포인트 잔액이 부족합니다: 잔액 %d, 요청 %d".formatted(balance, requested));
    }
}
