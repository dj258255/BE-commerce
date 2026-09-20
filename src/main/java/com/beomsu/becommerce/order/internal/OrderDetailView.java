package com.beomsu.becommerce.order.internal;

import java.time.Instant;
import java.util.List;

/**
 * 주문 상세 뷰 — {@code GET /api/v1/orders/{orderNo}}의 응답 본문(10-API-스펙 §1).
 *
 * <p>주문 애그리거트를 그대로 노출하지 않는 읽기 전용 record다. 결제 상태({@code paymentStatus})를
 * 함께 실어, 승인 결과(특히 202 UNKNOWN)를 이 조회로 확인할 수 있게 한다. 결제 시도가 없으면 null.
 */
public record OrderDetailView(String orderNo, String status, long totalAmount,
                              List<OrderItemView> items, String paymentStatus,
                              Instant expiresAt, Instant createdAt) {
}
