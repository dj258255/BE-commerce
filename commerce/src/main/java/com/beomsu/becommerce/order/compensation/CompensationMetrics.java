package com.beomsu.becommerce.order.compensation;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 보상 태스크 적체 게이지.
 *
 * <p>PENDING 태스크가 <b>얼마나 오래</b> 기다렸는지를 초 단위로 노출한다. 미확정 결제를 보류하면
 * ({@link CompensationOutcome#SKIPPED_UNRESOLVED}) 재시도 예산이 소진되지 않으므로, 미확정 결제가
 * 복구 배치에서 확정되지 않는 동안 태스크가 <b>FAILED 로도 가지 않고 무한정 PENDING 으로</b> 남는다.
 * 건수만 보면 정상처럼 보이지만, 가장 오래된 건의 나이가 계속 커지면 복구가 멈춘 것이다 —
 * {@code outbox.pending.oldest.age}·{@code payment.unknown.oldest.age} 와 같은 판단이다.
 *
 * <p>게이지 supplier는 스크레이프 스레드에서 매 수집마다 호출되므로 단일 집계 쿼리만 수행한다.
 */
@Component
public class CompensationMetrics {

    public CompensationMetrics(MeterRegistry meterRegistry, CompensationTaskRepository repository) {
        Gauge.builder("compensation.pending.oldest.age", this,
                        m -> repository.findOldestCreatedAt(CompensationStatus.PENDING)
                                .map(t -> (double) Math.max(0L, Duration.between(t, Instant.now()).getSeconds()))
                                .orElse(0.0))
                .baseUnit("seconds")
                .description("가장 오래 기다린 PENDING 보상 태스크의 나이(초). 없으면 0")
                .register(meterRegistry);
    }
}
