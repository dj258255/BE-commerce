package com.beomsu.becommerce.order.internal;

/**
 * 주문 항목 뷰 — 주문 시점 스냅샷(상품명·단가)의 읽기 전용 투영.
 *
 * <p>{@code OrderItem} 엔티티를 그대로 노출하지 않고, 조회에 필요한 필드만 담아 반환한다.
 */
public record OrderItemView(long productId, String productName, long unitPrice, int quantity) {
}
