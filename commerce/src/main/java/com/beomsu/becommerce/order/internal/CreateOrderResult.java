package com.beomsu.becommerce.order.internal;

import java.time.Instant;

/** 주문 생성 결과. orderNo는 PG 결제창의 orderId로 그대로 사용한다. */
public record CreateOrderResult(String orderNo, long totalAmount, Instant expiresAt) {
}
