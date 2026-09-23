package com.beomsu.becommerce.reconciliation.internal;

import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 승인 이벤트를 내부 기록으로 적재하는 리스너.
 *
 * <p>{@code @ApplicationModuleListener}로 결제 완료 이벤트를 받아, 대사의 기준이 되는 내부
 * 기대치({@link InternalRecord})를 쌓는다. 서비스가 (orderNo 유니크로) 멱등하게 적재한다.
 */
@Component
@RequiredArgsConstructor
class PaymentReconciliationListener {

    private final ReconciliationService reconciliationService;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent event) {
        reconciliationService.recordInternal(event);
    }

    /**
     * 취소도 구독해야 대사가 성립한다. 승인만 쌓으면 취소된 건은 PG 파일과 영영 안 맞는다 —
     * 부분취소는 금액 불일치로, 전액취소는 "내부에만 있음"으로 매일 다시 올라온다.
     */
    @ApplicationModuleListener
    void onCanceled(PaymentCanceledEvent event) {
        reconciliationService.reflectCancellation(event);
    }
}
