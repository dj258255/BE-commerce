package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import java.time.Instant;

/** 운영 어드민에 노출하는 정산 불일치(예외 큐) 뷰(엔티티 직접 노출 대신 필요한 필드만). */
public record ReconMismatchView(Long id, String orderNo, ReconResultType result,
                                Long internalAmount, Long externalAmount, Instant reconciledAt,
                                String resolvedBy, ResolveCause resolveCause, String resolveNote,
                                Instant resolvedAt) {
}
