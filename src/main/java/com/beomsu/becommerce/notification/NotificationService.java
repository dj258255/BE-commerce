package com.beomsu.becommerce.notification;

import com.beomsu.becommerce.notification.consumption.ProcessedEventRepository;
import com.beomsu.becommerce.notification.consumption.ProcessedEvent;
import com.beomsu.becommerce.notification.consumption.DeadLetterRepository;
import com.beomsu.becommerce.notification.consumption.DeadLetter;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 결제 완료 이벤트를 멱등하게 처리한다.
 *
 * <p>Outbox(Modulith 이벤트 레지스트리)는 at-least-once라 같은 이벤트가 중복 전달될 수 있다.
 * (eventKey, consumer) 유니크로 이미 처리한 이벤트는 건너뛴다. 발송이 실패하면 예외를 밖으로
 * 던지지 않고 <b>DLQ로 격리</b>한다 — 그래야 리스너가 계속 터지며 이벤트 처리를 막지 않는다.
 */
@Service
@RequiredArgsConstructor
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
    private static final String CONSUMER = "notification";

    private final ProcessedEventRepository processedEvents;
    private final DeadLetterRepository deadLetters;
    private final NotificationSender sender;

    @Transactional
    public void handlePaymentConfirmed(PaymentConfirmedEvent event) {
        String eventKey = "payment-confirmed-" + event.paymentId();

        // 멱등: 이미 처리한 이벤트면 아무 것도 하지 않는다.
        if (processedEvents.existsByEventKeyAndConsumer(eventKey, CONSUMER)) {
            return;
        }

        try {
            sender.sendPaymentReceipt(event.orderNo(), event.paymentId(), event.amount());
            processedEvents.save(ProcessedEvent.of(eventKey, CONSUMER));
        } catch (RuntimeException ex) {
            // 실패를 삼키고 DLQ로 격리 → 리스너는 정상 종료(Modulith가 이벤트를 완료로 마킹).
            // 격리된 건은 DLQ에서 운영/배치로 재처리한다.
            log.warn("결제 완료 알림 처리 실패 → DLQ 격리 eventKey={} : {}", eventKey, ex.getMessage());
            deadLetters.save(DeadLetter.of("PaymentConfirmedEvent", eventKey,
                    event.orderNo(), event.paymentId(), event.amount(), ex.getMessage()));
        }
    }
}
