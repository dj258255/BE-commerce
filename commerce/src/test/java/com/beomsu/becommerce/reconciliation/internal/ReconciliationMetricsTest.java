package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationMetrics;
import com.beomsu.becommerce.reconciliation.internal.ReconStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;

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
        when(repository.sumUnexplainedAmountByStatus(ReconStatus.PENDING)).thenReturn(0L);
        new ReconciliationMetrics(meterRegistry, repository);

        Gauge gauge = meterRegistry.find("recon.pending.count").gauge();
        assertThat(gauge).isNotNull();
        assertThat(meterRegistry.find("recon.pending.unexplained.amount").gauge()).isNotNull();
    }

    @Test
    @DisplayName("PENDING이 없으면 0을 반환한다")
    void zeroWhenNoPending() {
        when(repository.countByStatus(ReconStatus.PENDING)).thenReturn(0L);
        when(repository.sumUnexplainedAmountByStatus(ReconStatus.PENDING)).thenReturn(0L);
        new ReconciliationMetrics(meterRegistry, repository);

        assertThat(meterRegistry.get("recon.pending.count").gauge().value()).isEqualTo(0.0);
        assertThat(meterRegistry.get("recon.pending.unexplained.amount").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("PENDING 건수를 게이지로 노출한다")
    void exposesPendingCount() {
        when(repository.countByStatus(ReconStatus.PENDING)).thenReturn(3L);
        when(repository.sumUnexplainedAmountByStatus(ReconStatus.PENDING)).thenReturn(13_000L);
        new ReconciliationMetrics(meterRegistry, repository);

        assertThat(meterRegistry.get("recon.pending.count").gauge().value()).isEqualTo(3.0);
        assertThat(meterRegistry.get("recon.pending.unexplained.amount").gauge().value()).isEqualTo(13_000.0);
    }

    @Test
    @DisplayName("PENDING이 없으면 최장 경과 시간은 0이다")
    void oldestAgeZeroWhenNoPending() {
        when(repository.findTopByStatusOrderByReconciledAtAsc(ReconStatus.PENDING)).thenReturn(Optional.empty());
        new ReconciliationMetrics(meterRegistry, repository);

        assertThat(meterRegistry.get("recon.pending.oldest.age.seconds").gauge().value()).isZero();
    }

    @Test
    @DisplayName("건수가 한 건이어도 오래 묵었으면 경과 시간이 크다 — ReconPendingBacklog가 보는 값")
    void exposesOldestPendingAge() {
        ReconciliationResult oldest = ReconciliationResult.internalOnly(LocalDate.of(2026, 9, 22), "ORD-1", 10_000L);
        ReflectionTestUtils.setField(oldest, "reconciledAt", Instant.now().minus(Duration.ofHours(2)));
        when(repository.countByStatus(ReconStatus.PENDING)).thenReturn(1L);
        when(repository.findTopByStatusOrderByReconciledAtAsc(ReconStatus.PENDING)).thenReturn(Optional.of(oldest));
        new ReconciliationMetrics(meterRegistry, repository);

        assertThat(meterRegistry.get("recon.pending.count").gauge().value()).isEqualTo(1.0);
        assertThat(meterRegistry.get("recon.pending.oldest.age.seconds").gauge().value()).isBetween(7100.0, 7300.0);
    }
}
