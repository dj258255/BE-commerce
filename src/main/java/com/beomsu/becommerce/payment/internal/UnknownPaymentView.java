package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import java.time.Instant;

/** 운영 어드민에 노출하는 미확정(UNKNOWN) 결제 뷰(엔티티 직접 노출 대신 필요한 필드만). */
public record UnknownPaymentView(Long paymentId, String orderNo, long amount,
                                 PaymentStatus status, Instant requestedAt) {
}
