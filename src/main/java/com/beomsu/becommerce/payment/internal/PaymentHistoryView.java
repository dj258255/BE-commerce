package com.beomsu.becommerce.payment.internal;

import java.time.Instant;

/**
 * 결제 상태 전이 이력 뷰 — append-only인 {@code payment_history} 한 줄의 읽기 전용 투영.
 *
 * <p>엔티티({@code PaymentHistory})는 payment 모듈 내부(package-private)라 밖으로 노출하지 않는다.
 * 조회 응답에는 이 뷰 record만 실어, 모듈 경계 밖으로 JPA 엔티티가 새는 것을 막는다.
 */
public record PaymentHistoryView(String from, String to, String triggeredBy, Instant at) {
}
