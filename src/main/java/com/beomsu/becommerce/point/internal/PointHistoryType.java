package com.beomsu.becommerce.point.internal;

/**
 * 포인트 이력 유형.
 *
 * <ul>
 *   <li>{@link #USE} — 결제 시 포인트 차감(복합결제의 선점).</li>
 *   <li>{@link #RESTORE} — 카드 승인 실패 시 차감분을 되돌리는 보상.</li>
 *   <li>{@link #REFUND} — 부분취소 환불로 포인트를 되돌림.</li>
 *   <li>{@link #EARN} — 결제 완료 시 실결제액(카드+월렛) 기준으로 적립.</li>
 *   <li>{@link #EARN_REVERSAL} — 취소 시 그 몫만큼 적립을 회수(구매·취소 반복 포인트 파밍 방지).</li>
 * </ul>
 */
public enum PointHistoryType {
    USE,
    RESTORE,
    REFUND,
    EARN,
    EARN_REVERSAL
}
