package com.beomsu.becommerce.receipt;

import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 취소 → 현금영수증 연쇄 취소 리스너.
 * 결제만 취소되고 현금영수증이 남는 사고를 막는다(운영 함정 해소).
 */
@Component
@RequiredArgsConstructor
class PaymentCancelReceiptListener {

    private final ReceiptService receiptService;

    @ApplicationModuleListener
    void onCanceled(PaymentCanceledEvent event) {
        receiptService.cancelByOrder(event.orderNo());
    }
}
