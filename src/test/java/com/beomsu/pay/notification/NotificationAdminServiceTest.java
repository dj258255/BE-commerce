package com.beomsu.pay.notification;

import com.beomsu.pay.notification.consumption.DeadLetter;
import com.beomsu.pay.notification.consumption.DeadLetterRepository;
import com.beomsu.pay.notification.consumption.ProcessedEvent;
import com.beomsu.pay.notification.consumption.ProcessedEventRepository;
import com.beomsu.pay.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationAdminServiceTest {

    private DeadLetterRepository deadLetters;
    private ProcessedEventRepository processedEvents;
    private NotificationSender sender;
    private NotificationAdminService service;

    @BeforeEach
    void setUp() {
        deadLetters = mock(DeadLetterRepository.class);
        processedEvents = mock(ProcessedEventRepository.class);
        sender = mock(NotificationSender.class);
        service = new NotificationAdminService(deadLetters, processedEvents, sender);
    }

    private DeadLetter deadLetter() {
        return DeadLetter.of("PaymentConfirmedEvent", "payment-confirmed-100",
                "order-1", 100L, 10_000, "발송 채널 장애");
    }

    @Test
    @DisplayName("재처리 성공: 알림 재발송 + 처리완료 마킹 + DLQ에서 제거")
    void reprocessSuccess() {
        DeadLetter dl = deadLetter();
        when(deadLetters.findById(1L)).thenReturn(Optional.of(dl));

        boolean ok = service.reprocess(1L);

        assertThat(ok).isTrue();
        verify(sender).sendPaymentReceipt("order-1", 100L, 10_000);
        verify(processedEvents).save(any(ProcessedEvent.class));
        verify(deadLetters).delete(dl);
    }

    @Test
    @DisplayName("재처리 재실패: DLQ에 남기고 재시도 횟수만 증가")
    void reprocessStillFails() {
        DeadLetter dl = deadLetter();
        when(deadLetters.findById(1L)).thenReturn(Optional.of(dl));
        doThrow(new RuntimeException("여전히 장애"))
                .when(sender).sendPaymentReceipt(anyString(), anyLong(), anyLong());

        boolean ok = service.reprocess(1L);

        assertThat(ok).isFalse();
        assertThat(dl.getRetryCount()).isEqualTo(1);
        verify(deadLetters, never()).delete(any());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("이미 처리된 이벤트의 DLQ 항목은 재발송하지 않고 정리만 한다 — 알림 이중 발송 방지")
    void reprocessAlreadyProcessedDoesNotResend() {
        DeadLetter dl = deadLetter();
        when(deadLetters.findById(1L)).thenReturn(Optional.of(dl));
        when(processedEvents.existsByEventKeyAndConsumer("payment-confirmed-100", "notification"))
                .thenReturn(true);

        boolean ok = service.reprocess(1L);

        assertThat(ok).isTrue();
        verify(sender, never()).sendPaymentReceipt(anyString(), anyLong(), anyLong());
        verify(processedEvents, never()).save(any());
        verify(deadLetters).delete(dl);
    }

    @Test
    @DisplayName("복구 후 검증: 격리 건수와 최장 대기 시각을 낸다")
    void summaryReportsPendingAndOldest() {
        java.time.Instant oldest = java.time.Instant.parse("2026-09-18T00:00:00Z");
        when(deadLetters.count()).thenReturn(3L);
        when(deadLetters.findOldestCreatedAt()).thenReturn(Optional.of(oldest));

        var summary = service.summary();

        assertThat(summary.pendingCount()).isEqualTo(3L);
        assertThat(summary.oldestCreatedAt()).isEqualTo(oldest);
    }
}
