package com.beomsu.becommerce.order.internal;

import java.time.Instant;

/**
 * 주문 요약 뷰 — {@code GET /api/v1/orders}(내 주문 목록)의 각 항목.
 *
 * <p>목록에는 상세(품목·결제상태)까지 실을 필요가 없어, 주문번호·상태·총액·생성일만 담는 가벼운 record다.
 * 상세는 {@code GET /api/v1/orders/{orderNo}}로 따로 조회한다.
 */
public record OrderSummaryView(String orderNo, String status, long totalAmount, Instant createdAt) {

    static OrderSummaryView from(Order order) {
        return new OrderSummaryView(order.getOrderNo(), order.getStatus().name(),
                order.getTotalAmount(), order.getCreatedAt());
    }
}
