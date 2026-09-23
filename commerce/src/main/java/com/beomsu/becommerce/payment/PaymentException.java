package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.shared.DomainException;

/** 결제 도메인 예외. code는 10-API-스펙 문서의 에러 코드 체계와 일치한다. */
public class PaymentException extends DomainException {

    public PaymentException(String code, String message) {
        super(code, message);
    }

    public static PaymentException invalidTransition(PaymentStatus from, PaymentStatus to) {
        return new PaymentException("INVALID_STATE_TRANSITION",
                "허용되지 않은 상태 전이입니다: %s → %s".formatted(from, to));
    }

    public static PaymentException cancelAmountExceeded(long request, long balance) {
        return new PaymentException("CANCEL_AMOUNT_EXCEEDED",
                "취소 가능 잔액을 초과했습니다: 요청 %d, 잔액 %d".formatted(request, balance));
    }
}
