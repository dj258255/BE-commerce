package com.beomsu.becommerce.notification;

/** 결제 완료 알림 발송 추상화(이메일/푸시 등). 구현을 갈아끼울 수 있다. */
interface NotificationSender {
    void sendPaymentReceipt(String orderNo, Long paymentId, long amount);
}
