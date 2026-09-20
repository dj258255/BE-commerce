package com.beomsu.becommerce.subscription.internal;

import java.time.LocalDate;

/**
 * 구독 조회 뷰 — 엔티티 대신 노출한다. 빌링키(암호화 민감값)는 절대 싣지 않는다.
 */
public record SubscriptionView(
        Long id,
        long planAmount,
        String status,
        LocalDate currentPeriodStart,
        LocalDate nextBillingDate) {

    static SubscriptionView from(Subscription s) {
        return new SubscriptionView(s.getId(), s.getPlanAmount(), s.getStatus().name(),
                s.getCurrentPeriodStart(), s.getNextBillingDate());
    }
}
