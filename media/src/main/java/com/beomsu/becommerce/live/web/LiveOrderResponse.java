package com.beomsu.becommerce.live.web;

import java.time.Instant;

/** 주문 생성 응답(R11) — 결제 화면이 이어서 쓸 정보(기존 payments/confirm 흐름 그대로). */
public record LiveOrderResponse(String orderNo, long totalAmount, Instant expiresAt) {
}
