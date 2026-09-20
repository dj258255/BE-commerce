package com.beomsu.becommerce.fraud.review;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 심사 큐 게이지 — <b>건수만으로는 적체를 못 본다</b>는 것을 고정한다.
 */
class FraudReviewMetricsTest {

    private final FraudReviewRepository repository = mock(FraudReviewRepository.class);
    private final MeterRegistry registry = new SimpleMeterRegistry();

    private double gauge(String name) {
        return registry.get(name).gauge().value();
    }

    @Test
    @DisplayName("대기 중인 심사가 없으면 건수도 최장 대기 시간도 0이다")
    void zeroWhenQueueEmpty() {
        when(repository.countByStatus(FraudReviewStatus.PENDING)).thenReturn(0L);
        when(repository.findOldestCreatedAt(FraudReviewStatus.PENDING)).thenReturn(Optional.empty());

        new FraudReviewMetrics(registry, repository);

        assertThat(gauge("fraud.review.pending.count")).isZero();
        assertThat(gauge("fraud.review.oldest.age.seconds")).isZero();
    }

    @Test
    @DisplayName("가장 오래된 건의 대기 시간을 초로 낸다")
    void reportsOldestAge() {
        when(repository.countByStatus(FraudReviewStatus.PENDING)).thenReturn(3L);
        when(repository.findOldestCreatedAt(FraudReviewStatus.PENDING))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofHours(2))));

        new FraudReviewMetrics(registry, repository);

        assertThat(gauge("fraud.review.pending.count")).isEqualTo(3.0);
        assertThat(gauge("fraud.review.oldest.age.seconds")).isBetween(7100.0, 7300.0);
    }

    @Test
    @DisplayName("건수가 적어도 오래 묵었으면 대기 시간이 크다 — 알림이 잡아야 하는 자리")
    void oneOldItemStillAlerts() {
        when(repository.countByStatus(FraudReviewStatus.PENDING)).thenReturn(1L);
        when(repository.findOldestCreatedAt(FraudReviewStatus.PENDING))
                .thenReturn(Optional.of(Instant.now().minus(Duration.ofDays(2))));

        new FraudReviewMetrics(registry, repository);

        assertThat(gauge("fraud.review.pending.count")).isEqualTo(1.0);
        assertThat(gauge("fraud.review.oldest.age.seconds")).isGreaterThan(3600);
    }
}
