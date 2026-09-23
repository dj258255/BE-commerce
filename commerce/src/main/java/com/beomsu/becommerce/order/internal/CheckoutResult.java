package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.payment.PaymentStatus;

/** 결제 승인(체크아웃) 결과 — 주문 상태와 결제 상태를 함께 담아 컨트롤러가 HTTP 상태를 판단한다. */
public record CheckoutResult(String orderNo, OrderStatus orderStatus,
                             PaymentStatus paymentStatus, String message) {
}
