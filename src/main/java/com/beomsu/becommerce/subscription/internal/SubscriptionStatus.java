package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 구독 상태머신 (Google Play 구독 생명주기 참고).
 *
 * <p>결제 실패 시 곧바로 해지하지 않고 <b>유예기간(IN_GRACE_PERIOD) → 정지(ON_HOLD) → 만료(EXPIRED)</b>
 * 단계를 밟아 회수 기회를 준다. {@link PaymentStatus 결제 상태머신}과 동일하게 허용 전이만
 * {@link #TRANSITIONS}에 선언하고 {@link #canTransitionTo}가 불법 전이를 막는다.
 *
 * <p>ACTIVE → ON_HOLD 직행 간선은 <b>hard decline(도난/무효 카드) 즉시 정지</b> 전용이다. soft decline은
 * ACTIVE → IN_GRACE_PERIOD → ON_HOLD의 정상 dunning 경로를 따르지만, hard decline은 유예/재시도 없이
 * 곧장 정지시켜야 하므로(카드사 평판 보호) 이 직행 간선을 둔다.
 */
public enum SubscriptionStatus {

    /** 정상 구독 — 매 주기 청구된다 */
    ACTIVE,
    /** 유예기간 — soft decline 후 접근은 유지하며 재청구를 시도한다 */
    IN_GRACE_PERIOD,
    /** 정지 — 재시도 소진 또는 hard decline. 접근 차단, 복구 시 재개 */
    ON_HOLD,
    /** 해지 (terminal) — 사용자/가맹점이 중단 */
    CANCELED,
    /** 만료 (terminal) — 정지에서 회수 실패로 종료 */
    EXPIRED;

    private static final Map<SubscriptionStatus, Set<SubscriptionStatus>> TRANSITIONS = Map.of(
            ACTIVE,          EnumSet.of(IN_GRACE_PERIOD, ON_HOLD, CANCELED), // ON_HOLD 직행 = hard decline
            IN_GRACE_PERIOD, EnumSet.of(ACTIVE, ON_HOLD),                    // ACTIVE = 결제 성공 복귀
            ON_HOLD,         EnumSet.of(ACTIVE, EXPIRED),
            CANCELED,        Collections.emptySet(),
            EXPIRED,         Collections.emptySet()
    );

    public boolean canTransitionTo(SubscriptionStatus target) {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).isEmpty();
    }
}
