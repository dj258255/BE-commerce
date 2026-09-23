package com.beomsu.becommerce.ledger.internal;

import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 사건을 원장에 반영하는 리스너.
 *
 * <p>{@code @ApplicationModuleListener}로 결제 완료·취소 이벤트를 받아 분개한다. Modulith의
 * 이벤트 레지스트리(=Outbox)가 유실을 막고, 원장 서비스가 (source 유니크로) 멱등하게 기록한다.
 */
@Component
@RequiredArgsConstructor
class PaymentLedgerListener {

    private final LedgerService ledgerService;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent event) {
        ledgerService.recordPaymentConfirmed(event);
    }

    @ApplicationModuleListener
    void onCanceled(PaymentCanceledEvent event) {
        ledgerService.recordPaymentCanceled(event);
    }
}
