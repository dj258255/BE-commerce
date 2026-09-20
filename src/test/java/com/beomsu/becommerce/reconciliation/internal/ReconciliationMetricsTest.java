package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationMetrics;
import com.beomsu.becommerce.reconciliation.internal.ReconStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReconciliationMetricsTest {

    private ReconciliationResultRepository repository;
    private SimpleMeterRegistry meterRegistry;

    @BeforeEach
    void setUp() {
        repository = mock(ReconciliationResultRepository.class);
        meterRegistry = new SimpleMeterRegistry();
    }

    @Test
    @DisplayName("게이지가 레지스트리에 등록된다")
    void registersGauge() {
        when(repository.countByStatus(ReconStatus.PENDING)).thenReturn(0L);
        new ReconciliationMetrics(meterRegistry, repository);

        Gauge gauge = meterRegistry.find("recon.pending.count").gauge();
        assertThat(gauge).isNotNull();
    }

    @Test
    @DisplayName("PENDING이 없으면 0을 반환한다")
    void zeroWhenNoPending() {
        when(repository.countByStatus(ReconStatus.PENDING)).thenReturn(0L);
        new ReconciliationMetrics(meterRegistry, repository);

        assertThat(meterRegistry.get("recon.pending.count").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("PENDING 건수를 게이지로 노출한다")
    void exposesPendingCount() {
        when(repository.countByStatus(ReconStatus.PENDING)).thenReturn(3L);
        new ReconciliationMetrics(meterRegistry, repository);

        assertThat(meterRegistry.get("recon.pending.count").gauge().value()).isEqualTo(3.0);
    }
}
