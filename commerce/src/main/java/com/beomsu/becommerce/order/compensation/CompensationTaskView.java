package com.beomsu.becommerce.order.compensation;

import java.time.Instant;

/** 운영 어드민에 노출하는 보상 태스크 뷰(엔티티 직접 노출 대신 필요한 필드만). */
public record CompensationTaskView(Long id, String orderNo, long amount, CompensationStatus status,
                                   int retryCount, String lastError, Instant nextAttemptAt) {
}
