package com.beomsu.becommerce.payment.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 결제 SLO 게이지.
 *
 * <p>미확정(UNKNOWN) 결제가 얼마나 오래 방치됐는지를 초 단위로 노출한다. 복구 배치가 밀리거나
 * 망취소가 지연되면 이 값이 커지고, Prometheus 알림(UnknownPaymentAging)이 뜬다.
 * 게이지 supplier는 스크레이프 스레드에서 매 수집마다 호출되므로 단일 집계 쿼리만 수행한다.
 */
@Component
public class PaymentSloMetrics {

    private final PaymentRepository paymentRepository;

    public PaymentSloMetrics(MeterRegistry meterRegistry, PaymentRepository paymentRepository) {
        this.paymentRepository = paymentRepository;
        Gauge.builder("payment.unknown.oldest.age", this, PaymentSloMetrics::unknownOldestAgeSeconds)
                .baseUnit("seconds")
                .description("가장 오래된 UNKNOWN(미확정) 결제의 경과 시간(초). 미확정이 없으면 0")
                .register(meterRegistry);
    }

    /** UNKNOWN 중 가장 오래된 건의 나이(초). 미확정이 없거나 음수면 0. */
    double unknownOldestAgeSeconds() {
        return paymentRepository.findOldestUnknownRequestedAt()
                .map(oldest -> Math.max(0L, Duration.between(oldest, Instant.now()).getSeconds()))
                .orElse(0L)
                .doubleValue();
    }
}
