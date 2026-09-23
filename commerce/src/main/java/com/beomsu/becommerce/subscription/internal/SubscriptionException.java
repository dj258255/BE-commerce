package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.shared.DomainException;

/** 구독 도메인 예외. code는 API 에러 코드 체계와 일치한다. */
public class SubscriptionException extends DomainException {

    public SubscriptionException(String code, String message) {
        super(code, message);
    }

    public static SubscriptionException invalidTransition(SubscriptionStatus from, SubscriptionStatus to) {
        return new SubscriptionException("INVALID_SUBSCRIPTION_TRANSITION",
                "허용되지 않은 구독 상태 전이입니다: %s → %s".formatted(from, to));
    }

    public static SubscriptionException notActive(SubscriptionStatus current) {
        return new SubscriptionException("SUBSCRIPTION_NOT_ACTIVE",
                "ACTIVE 구독에서만 가능한 작업입니다. 현재 상태: %s".formatted(current));
    }

    public static SubscriptionException notFound(Long id) {
        return new SubscriptionException("SUBSCRIPTION_NOT_FOUND",
                "구독을 찾을 수 없습니다: " + id);
    }
}
