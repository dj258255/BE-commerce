package com.beomsu.becommerce.notification;

import com.beomsu.becommerce.notification.consumption.ProcessedEvent;
import com.beomsu.becommerce.notification.consumption.ProcessedEventRepository;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceTest {

    private ProcessedEventRepository processedEvents;
    private NotificationSender sender;
    private NotificationService service;

    private final PaymentConfirmedEvent event =
            new PaymentConfirmedEvent("order-1", 100L, 10_000, Instant.now());

    @BeforeEach
    void setUp() {
        processedEvents = mock(ProcessedEventRepository.class);
        sender = mock(NotificationSender.class);
        service = new NotificationService(processedEvents, sender);
    }

    @Test
    @DisplayName("신규 이벤트: 이력을 발송보다 먼저 적고 발송한다")
    void newEventRecordsThenSends() {
        when(processedEvents.existsByEventKeyAndConsumer(anyString(), anyString())).thenReturn(false);

        service.handlePaymentConfirmed(event);

        // 순서가 멱등의 핵심이다 — insert(강제 flush)가 발송보다 앞선다.
        InOrder order = inOrder(processedEvents, sender);
        order.verify(processedEvents).saveAndFlush(any(ProcessedEvent.class));
        order.verify(sender).sendPaymentReceipt("order-1", 100L, 10_000);
    }

    @Test
    @DisplayName("중복 이벤트: 이미 처리했으면 발송하지 않는다 (멱등 컨슈머)")
    void duplicateEventSkipped() {
        when(processedEvents.existsByEventKeyAndConsumer(anyString(), anyString())).thenReturn(true);

        service.handlePaymentConfirmed(event);

        verify(sender, never()).sendPaymentReceipt(anyString(), anyLong(), anyLong());
        verify(processedEvents, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("발송 실패: 예외를 전파해 롤백·재전달에 맡긴다 — 이력도 함께 롤백된다(at-least-once)")
    void sendFailurePropagatesForRedelivery() {
        when(processedEvents.existsByEventKeyAndConsumer(anyString(), anyString())).thenReturn(false);
        doThrow(new RuntimeException("발송 채널 장애"))
                .when(sender).sendPaymentReceipt(anyString(), anyLong(), anyLong());

        // 삼키지 않고 던져야 트랜잭션이 롤백되고(이력 insert 도 함께 사라짐) 재전달이 다시 시도한다.
        assertThatThrownBy(() -> service.handlePaymentConfirmed(event))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("발송 채널 장애");

        verify(processedEvents).saveAndFlush(any(ProcessedEvent.class)); // 시도됐고, 롤백으로 함께 사라진다
    }
}
