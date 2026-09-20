package com.beomsu.becommerce.reconciliation.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 대사 SLO 게이지.
 *
 * <p>사람 확인이 필요한 PENDING 예외 큐의 적체를 노출한다. 이 값이 오래 0을 넘으면
 * Prometheus 알림(ReconPendingBacklog)이 뜬다. 게이지 supplier는 스크레이프마다
 * 단일 count 쿼리만 수행한다.
 */
@Component
public class ReconciliationMetrics {

    public ReconciliationMetrics(MeterRegistry meterRegistry, ReconciliationResultRepository repository) {
        Gauge.builder("recon.pending.count", this, m -> repository.countByStatus(ReconStatus.PENDING))
                .description("사람 확인이 필요한 PENDING(미해결) 대사 건수")
                .register(meterRegistry);
    }
}
