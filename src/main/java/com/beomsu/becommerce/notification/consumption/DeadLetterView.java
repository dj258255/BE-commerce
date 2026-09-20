package com.beomsu.becommerce.notification.consumption;

import java.time.Instant;

/** 백오피스 어드민에 노출하는 DLQ 항목 뷰. */
public record DeadLetterView(Long id, String eventType, String eventKey, String orderNo,
                             Long paymentId, long amount, String failReason,
                             int retryCount, Instant createdAt) {
}
