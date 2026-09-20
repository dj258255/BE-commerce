package com.beomsu.becommerce.dispute;

/**
 * 분쟁 패소(LOST) 이벤트. ledger 모듈이 구독해 원매출을 역분개(reversal)한다.
 * Zero-Payload 지향 — 식별자와 금액만 담아 모듈 간 스키마 결합을 피한다.
 *
 * <p>dispute는 ledger에 의존하지 않는다. 이 이벤트를 발행하기만 하고, 소비는 ledger가 한다
 * ({@code @ApplicationModuleListener} + 아웃박스로 유실 없이). {@code disputeId}는 원장 멱등키의
 * {@code sourceId}로 쓰여 같은 패소가 두 번 역분개되지 않게 한다.
 */
public record DisputeLostEvent(String orderNo, Long paymentId, long amount, Long disputeId) {
}
