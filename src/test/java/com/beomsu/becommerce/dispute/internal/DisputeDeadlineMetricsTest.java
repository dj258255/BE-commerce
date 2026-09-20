package com.beomsu.becommerce.dispute.internal;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 알림이 보는 지표 이름과 부호를 지킨다.
 *
 * <p>보는 곳: {@code monitoring/alert-rules.yml} 의 {@code DisputeDeadlineApproaching}(48시간 미만)과
 * {@code DisputeDeadlineMissed}(음수). <b>지난 기한을 0 으로 깎으면 두 알림이 구별되지 않는다.</b>
 */
@DisplayName("이의제기 기한 지표 — 임박과 초과가 구별되는지")
class DisputeDeadlineMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private double gauge(String name) {
        var g = registry.find(name).gauge();
        return g == null ? Double.NaN : g.value();
    }

    private DisputeRepository repositoryWith(Instant... deadlines) {
        DisputeRepository repo = mock(DisputeRepository.class);
        List<Dispute> rows = java.util.Arrays.stream(deadlines)
                .map(d -> Dispute.open("cb-" + d.toEpochMilli(), "ORD-1", 1L, 10_000L, "미수취", d))
                .toList();
        when(repo.findByStatus(any())).thenReturn(rows);
        return repo;
    }

    @Test
    @DisplayName("기한이 남아 있으면 양수로, 가장 임박한 것을 낸다")
    void reportsNearestRemaining() {
        Instant now = Instant.now();
        new DisputeDeadlineMetrics(registry,
                repositoryWith(now.plus(Duration.ofDays(5)), now.plus(Duration.ofHours(6))));

        assertThat(gauge("dispute.open.count")).isEqualTo(2);
        // 6시간이 5일보다 임박하다.
        assertThat(gauge("dispute.nearest.deadline.seconds"))
                .isBetween(Duration.ofHours(5).toSeconds() * 1.0, Duration.ofHours(6).toSeconds() * 1.0);
    }

    @Test
    @DisplayName("이미 지난 기한은 음수로 낸다 — 0으로 깎으면 지났다와 빠듯하다가 같아진다")
    void reportsMissedDeadlineAsNegative() {
        new DisputeDeadlineMetrics(registry,
                repositoryWith(Instant.now().minus(Duration.ofDays(1))));

        assertThat(gauge("dispute.nearest.deadline.seconds")).isNegative();
    }

    @Test
    @DisplayName("대상이 없으면 0 — 임박도 초과도 아니다")
    void reportsZeroWhenNothingOpen() {
        DisputeRepository repo = mock(DisputeRepository.class);
        when(repo.findByStatus(any())).thenReturn(List.of());
        new DisputeDeadlineMetrics(registry, repo);

        assertThat(gauge("dispute.open.count")).isZero();
        assertThat(gauge("dispute.nearest.deadline.seconds")).isZero();
    }
}
