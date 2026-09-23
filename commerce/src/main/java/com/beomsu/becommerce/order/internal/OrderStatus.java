package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.PaymentStatus;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 주문 상태머신.
 *
 * <p>결제 상태머신({@code PaymentStatus})과는 별개의 상태머신이다 — 주문은 비즈니스 관점(확정·취소),
 * 결제는 자금 관점. 허용된 전이만 {@link #TRANSITIONS}에 선언하고, 불법 전이는
 * {@link #canTransitionTo}가 막는다({@code Payment}와 동일한 가드 방식).
 */
public enum OrderStatus {

    /** 주문 생성 직후 초기 상태 (결제 대기 이전) */
    CREATED,
    /** 결제 대기 — totalAmount가 확정되어 금액 위변조 검증의 기준값이 되는 상태 */
    PENDING_PAYMENT,
    /** 승인 진행 중 — 조건부 전이로 이중 지불을 차단하는 잠금 구간 */
    PAYMENT_IN_PROGRESS,
    /** 결제 완료 (최종은 아님 — 취소로 전이 가능) */
    PAID,
    /** 취소 (terminal) */
    CANCELED,
    /** 유효시간(30분) 경과 (terminal) */
    EXPIRED,
    /** 승인 실패 확정 (terminal) */
    FAILED;

    private static final Map<OrderStatus, Set<OrderStatus>> TRANSITIONS = Map.of(
            CREATED,             EnumSet.of(PENDING_PAYMENT, EXPIRED),
            PENDING_PAYMENT,     EnumSet.of(PAYMENT_IN_PROGRESS, EXPIRED),
            PAYMENT_IN_PROGRESS, EnumSet.of(PAID, PENDING_PAYMENT, FAILED), // 승인 실패 시 PENDING_PAYMENT로 복귀
            PAID,                EnumSet.of(CANCELED),
            CANCELED,            Collections.emptySet(),
            EXPIRED,             Collections.emptySet(),
            FAILED,              Collections.emptySet()
    );

    public boolean canTransitionTo(OrderStatus target) {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).isEmpty();
    }
}
