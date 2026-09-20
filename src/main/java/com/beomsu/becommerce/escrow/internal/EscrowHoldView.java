package com.beomsu.becommerce.escrow.internal;

import java.time.Instant;

/**
 * 에스크로 홀드 조회용 뷰 — 엔티티를 노출하지 않고 상태 관측에 필요한 값만 담는다.
 *
 * @param orderNo       주문 번호
 * @param amount        보류 금액
 * @param status        현재 상태(HELD/RELEASED/REFUNDED)
 * @param heldAt        보류 시작 시각
 * @param autoReleaseAt 자동 구매확정 예정 시각
 * @param resolvedAt    종결 시각(HELD 동안은 null)
 */
public record EscrowHoldView(String orderNo, long amount, EscrowStatus status,
                             Instant heldAt, Instant autoReleaseAt, Instant resolvedAt) {

    public static EscrowHoldView from(EscrowHold hold) {
        return new EscrowHoldView(hold.getOrderNo(), hold.getAmount(), hold.getStatus(),
                hold.getHeldAt(), hold.getAutoReleaseAt(), hold.getResolvedAt());
    }
}
