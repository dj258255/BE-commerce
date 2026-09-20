package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.subscription.dunning.DunningAttempt;
import java.time.LocalDate;
import java.util.List;

/**
 * 구독 상세 뷰 — 구독 정보 + 청구 이력(dunning 시도)을 함께 노출한다.
 */
public record SubscriptionDetailView(
        Long id,
        long planAmount,
        String status,
        LocalDate currentPeriodStart,
        LocalDate nextBillingDate,
        List<BillingAttemptView> billingHistory) {

    /** 청구 시도 1건. */
    public record BillingAttemptView(int attemptNo, String result, LocalDate nextRetryAt) {
        static BillingAttemptView from(DunningAttempt a) {
            return new BillingAttemptView(a.getAttemptNo(), a.getResult().name(), a.getNextRetryAt());
        }
    }

    static SubscriptionDetailView of(Subscription s, List<DunningAttempt> attempts) {
        return new SubscriptionDetailView(s.getId(), s.getPlanAmount(), s.getStatus().name(),
                s.getCurrentPeriodStart(), s.getNextBillingDate(),
                attempts.stream().map(BillingAttemptView::from).toList());
    }
}
