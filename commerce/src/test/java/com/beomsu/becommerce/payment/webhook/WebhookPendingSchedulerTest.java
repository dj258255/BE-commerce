package com.beomsu.becommerce.payment.webhook;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WebhookPendingSchedulerTest {

    private WebhookEventRepository repository;
    private WebhookService webhookService;
    private SimpleMeterRegistry meterRegistry;
    private WebhookPendingScheduler scheduler;

    @BeforeEach
    void setUp() {
        repository = mock(WebhookEventRepository.class);
        webhookService = mock(WebhookService.class);
        meterRegistry = new SimpleMeterRegistry();
        scheduler = new WebhookPendingScheduler(repository, webhookService, meterRegistry);
        ReflectionTestUtils.setField(scheduler, "maxRetry", 12);
    }

    /** 보류를 {@code retries} 회 반복한(=retryCount=retries) PENDING_PAYMENT 이벤트. */
    private WebhookEvent pendingWithRetries(int retries) {
        WebhookEvent event = WebhookEvent.received("evt-" + retries, "PAYMENT_STATUS_CHANGED", "{}");
        for (int i = 0; i < retries; i++) {
            event.markPendingPayment(Instant.now());
        }
        return event;
    }

    private void repositoryReturns(WebhookEvent event) {
        when(repository.findByStatusAndNextRetryAtLessThanEqual(
                eq(WebhookEventStatus.PENDING_PAYMENT), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(event));
    }

    @Test
    @DisplayName("보류 재시도 소진(12회) → FAILED 전이 + payment.webhook.pending.exhausted 증가")
    void exhaustionMarksFailedAndCounts() {
        WebhookEvent event = pendingWithRetries(12);
        repositoryReturns(event);

        scheduler.retryPending();

        assertThat(event.getStatus()).isEqualTo(WebhookEventStatus.FAILED);
        assertThat(meterRegistry.counter("payment.webhook.pending.exhausted").count()).isEqualTo(1.0);
        verify(repository).save(event);
        verify(webhookService, never()).process(any()); // 소진 건은 더 재처리하지 않는다
    }

    @Test
    @DisplayName("상한 미만이면 재처리하고 소진 카운터는 오르지 않는다")
    void belowLimitRetriesWithoutCounting() {
        WebhookEvent event = pendingWithRetries(3);
        repositoryReturns(event);

        scheduler.retryPending();

        verify(webhookService).process(event);
        assertThat(meterRegistry.find("payment.webhook.pending.exhausted").counter()).isNull();
    }
}
