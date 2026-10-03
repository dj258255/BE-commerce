package com.beomsu.becommerce.payment.webhook;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WebhookMetricsTest {

    @Test
    @DisplayName("FAILED 웹훅 수·최장 나이 게이지를 등록한다")
    void registersFailedGauges() {
        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        when(repository.countByStatus(WebhookEventStatus.FAILED)).thenReturn(3L);
        when(repository.findOldestProcessedAt(WebhookEventStatus.FAILED))
                .thenReturn(Optional.of(Instant.now().minusSeconds(120)));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new WebhookMetrics(registry, repository);

        assertThat(registry.get("payment.webhook.failed.count").gauge().value()).isEqualTo(3.0);
        assertThat(registry.get("payment.webhook.failed.oldest.age").gauge().value())
                .isBetween(119.0, 121.0);
    }

    @Test
    @DisplayName("FAILED 가 없으면 나이 게이지는 0(알림이 빈 상태에서 오발화하지 않게)")
    void oldestAgeIsZeroWhenNoFailed() {
        WebhookEventRepository repository = mock(WebhookEventRepository.class);
        when(repository.findOldestProcessedAt(WebhookEventStatus.FAILED)).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        new WebhookMetrics(registry, repository);

        assertThat(registry.get("payment.webhook.failed.oldest.age").gauge().value()).isEqualTo(0.0);
    }
}
