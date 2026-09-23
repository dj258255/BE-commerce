package com.beomsu.becommerce.notification;

import com.beomsu.becommerce.notification.consumption.DeadLetter;
import com.beomsu.becommerce.notification.consumption.DeadLetterRepository;
import com.beomsu.becommerce.notification.consumption.ProcessedEvent;
import com.beomsu.becommerce.notification.consumption.ProcessedEventRepository;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private ProcessedEventRepository processedEvents;
    private DeadLetterRepository deadLetters;
    private NotificationSender sender;
    private NotificationService service;

    private final PaymentConfirmedEvent event =
            new PaymentConfirmedEvent("order-1", 100L, 10_000, Instant.now());

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEventRepository.class);
        deadLetters = mock(DeadLetterRepository.class);
        sender = mock(NotificationSender.class);
        service = new NotificationService(processedEvents, deadLetters, sender);
    }

    @Test
    @DisplayName("신규 이벤트: 알림 발송 + 처리 완료 기록")
    void newEventProcessed() {
        when(processedEvents.existsByEventKeyAndConsumer(anyString(), anyString())).thenReturn(false);

        service.handlePaymentConfirmed(event);

        verify(sender).sendPaymentReceipt("order-1", 100L, 10_000);
        verify(processedEvents).save(any(ProcessedEvent.class));
        verify(deadLetters, never()).save(any());
    }

    @Test
    @DisplayName("중복 이벤트: 이미 처리했으면 발송하지 않는다 (멱등 컨슈머)")
    void duplicateEventSkipped() {
        when(processedEvents.existsByEventKeyAndConsumer(anyString(), anyString())).thenReturn(true);

        service.handlePaymentConfirmed(event);

        verify(sender, never()).sendPaymentReceipt(anyString(), anyLong(), anyLong());
        verify(processedEvents, never()).save(any());
    }

    @Test
    @DisplayName("발송 실패: 예외를 던지지 않고 DLQ로 격리한다")
    void failureGoesToDlq() {
        when(processedEvents.existsByEventKeyAndConsumer(anyString(), anyString())).thenReturn(false);
        doThrow(new RuntimeException("발송 채널 장애"))
                .when(sender).sendPaymentReceipt(anyString(), anyLong(), anyLong());

        service.handlePaymentConfirmed(event);   // 예외가 밖으로 나오지 않아야 한다

        verify(deadLetters).save(any(DeadLetter.class));
        verify(processedEvents, never()).save(any());   // 성공 마킹 안 됨
    }
}
