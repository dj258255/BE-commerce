package com.beomsu.becommerce.reconciliation.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
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
        Gauge.builder("recon.pending.unexplained.amount", this,
                        m -> repository.sumUnexplainedAmountByStatus(ReconStatus.PENDING))
                .description("PENDING 대사 건에서 아직 설명되지 않은 내부·외부 금액 차이의 절대값 합")
                .register(meterRegistry);
        Gauge.builder("recon.pending.oldest.age.seconds", this, m -> repository
                        .findTopByStatusOrderByReconciledAtAsc(ReconStatus.PENDING)
                        .map(result -> Math.max(0L, Duration.between(result.getReconciledAt(), Instant.now()).toSeconds()))
                        .orElse(0L))
                .description("가장 오래된 PENDING 대사 건의 경과 시간(초)")
                .register(meterRegistry);
    }
}
