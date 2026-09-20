package com.beomsu.becommerce.payment.internal;

import java.time.Instant;

/**
 * 결제 취소 뷰 — 취소 전이(→ CANCELED/PARTIAL_CANCELED)를 상태 이력에서 투영한 것.
 *
 * <p>이 시스템은 별도 취소 엔티티({@code payment_cancels})를 두지 않고 상태 이력 한 줄로 취소를
 * 표현한다. 그래서 취소 금액은 이력만으로는 알 수 없어(잔액 변화로만 유추 가능) 최소 필드
 * (전이 대상 상태·사유·시각)만 노출한다 — 과설계하지 않는다.
 */
public record PaymentCancelView(String to, String reason, Instant at) {
}
