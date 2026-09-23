package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.internal.PaymentSloMetrics;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PaymentSloMetricsTest {

    private PaymentRepository repository;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("게이지가 레지스트리에 등록된다")
    void registersGauge() {
        when(repository.findOldestUnknownRequestedAt()).thenReturn(Optional.empty());
        new PaymentSloMetrics(meterRegistry, repository);

        Gauge gauge = meterRegistry.find("payment.unknown.oldest.age").gauge();
        assertThat(gauge).isNotNull();
    }

    @Test
    @DisplayName("UNKNOWN 결제가 없으면 0을 반환한다")
    void zeroWhenNoUnknown() {
        when(repository.findOldestUnknownRequestedAt()).thenReturn(Optional.empty());
        PaymentSloMetrics metrics = new PaymentSloMetrics(meterRegistry, repository);

        assertThat(metrics.unknownOldestAgeSeconds()).isEqualTo(0.0);
        assertThat(meterRegistry.get("payment.unknown.oldest.age").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("가장 오래된 UNKNOWN의 나이(초)를 반환한다")
    void returnsAgeSecondsForOldest() {
        when(repository.findOldestUnknownRequestedAt())
                .thenReturn(Optional.of(Instant.now().minusSeconds(120)));
        PaymentSloMetrics metrics = new PaymentSloMetrics(meterRegistry, repository);

        // 최소 120초 경과(테스트 실행 지연으로 약간 더 클 수 있음).
        assertThat(metrics.unknownOldestAgeSeconds()).isGreaterThanOrEqualTo(120.0);
    }

    @Test
    @DisplayName("미래 시각(음수 경과)은 0으로 보정한다")
    void clampsNegativeToZero() {
        when(repository.findOldestUnknownRequestedAt())
                .thenReturn(Optional.of(Instant.now().plusSeconds(60)));
        PaymentSloMetrics metrics = new PaymentSloMetrics(meterRegistry, repository);

        assertThat(metrics.unknownOldestAgeSeconds()).isEqualTo(0.0);
    }
}
