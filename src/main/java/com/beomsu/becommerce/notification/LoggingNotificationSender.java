package com.beomsu.becommerce.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** 기본 구현 — 로그로 대체(실제 발송 채널은 추후). */
@Component
class LoggingNotificationSender implements NotificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

    @Override
    public void sendPaymentReceipt(String orderNo, Long paymentId, long amount) {
        log.info("결제 완료 알림 발송 orderNo={} paymentId={} amount={}", orderNo, paymentId, amount);
    }
}
