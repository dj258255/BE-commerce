package com.beomsu.becommerce.wallet.internal;

/**
 * 월렛 거래 유형.
 *
 * <ul>
 *   <li>{@link #CHARGE} — 선불 충전(잔액 증가). 전금법 기명 200만원 한도 검증 대상.</li>
 *   <li>{@link #USE} — 결제 시 잔액 차감. 마이너스 잔액이 될 수 없다.</li>
 *   <li>{@link #RESTORE} — 사가 보상(승인 실패·재고 부족)으로 USE 예약을 되돌림(주문 단위 멱등).</li>
 *   <li>{@link #REFUND} — 완료된 결제의 취소 환불로 잔액을 되돌림(부분취소 다회 가능, 비멱등).</li>
 * </ul>
 */
public enum WalletTransactionType {
    CHARGE,
    USE,
    RESTORE,
    REFUND
}
