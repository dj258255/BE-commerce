package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.internal.Payment;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 결제 상태머신.
 *
 * <p>토스페이먼츠 Payment.status를 기반으로 하되, 타임아웃을 성공도 실패도 아닌
 * {@link #UNKNOWN}으로 1급 시민화했다(카카오페이 3-상태 모델). 허용된 전이만
 * {@link #TRANSITIONS}에 선언하고, 불법 전이는 {@link #canTransitionTo}가 막는다.
 */
public enum PaymentStatus {

    /** 결제 생성 초기 상태 */
    READY,
    /** 인증 완료 — 이 상태에서 승인 API를 호출한다 */
    IN_PROGRESS,
    /** 승인 미확정 — PG 타임아웃/응답 유실. 복구 배치·망취소의 대상 */
    UNKNOWN,
    /** 승인 완료 (최종은 아님 — 취소로 전이 가능) */
    DONE,
    /** 전액 취소 */
    CANCELED,
    /** 부분 취소 (추가 부분취소·전액취소로 전이 가능) */
    PARTIAL_CANCELED,
    /** 승인 실패 (terminal) */
    ABORTED,
    /** 유효시간(30분) 경과 (terminal) */
    EXPIRED;

    private static final Map<PaymentStatus, Set<PaymentStatus>> TRANSITIONS = Map.of(
            READY,            EnumSet.of(IN_PROGRESS, EXPIRED, ABORTED),
            IN_PROGRESS,      EnumSet.of(DONE, UNKNOWN, ABORTED, EXPIRED),
            UNKNOWN,          EnumSet.of(DONE, CANCELED, ABORTED),      // 복구 배치가 조회 후 확정/망취소
            DONE,             EnumSet.of(CANCELED, PARTIAL_CANCELED),
            PARTIAL_CANCELED, EnumSet.of(PARTIAL_CANCELED, CANCELED),
            CANCELED,         Collections.emptySet(),
            ABORTED,          Collections.emptySet(),
            EXPIRED,          Collections.emptySet()
    );

    public boolean canTransitionTo(PaymentStatus target) {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).contains(target);
    }

    public boolean isTerminal() {
        return TRANSITIONS.getOrDefault(this, Collections.emptySet()).isEmpty();
    }
}
