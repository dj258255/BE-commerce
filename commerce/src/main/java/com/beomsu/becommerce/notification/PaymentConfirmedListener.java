package com.beomsu.becommerce.notification;

import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 완료 이벤트 리스너.
 *
 * <p>{@code @ApplicationModuleListener}는 (1) 발행 트랜잭션 커밋 이후에 (2) 비동기로 (3) 자신의
 * 트랜잭션에서 실행되며, Spring Modulith가 이벤트를 {@code event_publication} 테이블(= Outbox)에
 * 기록해 유실을 방지한다. 리스너가 정상 종료해야 이벤트가 완료로 마킹된다 — 그래서 실패는
 * {@link NotificationService} 안에서 DLQ로 흡수한다.
 */
@Component
@RequiredArgsConstructor
class PaymentConfirmedListener {

    private final NotificationService notificationService;

    @ApplicationModuleListener
    void on(PaymentConfirmedEvent event) {
        notificationService.handlePaymentConfirmed(event);
    }
}
