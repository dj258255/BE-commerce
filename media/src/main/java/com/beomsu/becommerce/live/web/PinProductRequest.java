package com.beomsu.becommerce.live.web;

/** 상품 고정 요청(R8.1) — 방송 특가(원)와 한정 수량. */
public record PinProductRequest(long productId, long price, int limitedQuantity) {
}
