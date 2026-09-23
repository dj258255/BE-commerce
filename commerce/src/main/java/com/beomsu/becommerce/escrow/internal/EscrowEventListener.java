package com.beomsu.becommerce.escrow.internal;

import com.beomsu.becommerce.escrow.EscrowService;
import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 이벤트를 에스크로 보류 생명주기로 잇는 리스너.
 *
 * <p>승인({@link PaymentConfirmedEvent})이면 결제금을 HELD로 보류하고, <b>전액</b> 취소
 * ({@link PaymentCanceledEvent#fullyCanceled()})면 아직 보류 중인 홀드를 REFUNDED로 환불한다 —
 * 구매확정 전 취소는 판매자에게 정산되지 않게 회수한다. 부분취소는 홀드를 유지한다(에스크로는 주문
 * 단위 all-or-nothing 보류라, 금액 일부 환불이 홀드 전체 회수로 이어지면 안 된다). Outbox(Event
 * Publication Registry)가 유실을 막고, 서비스가 (orderNo 기준) 멱등하게 처리한다.
 */
@Component
@RequiredArgsConstructor
class EscrowEventListener {

    private final EscrowService escrowService;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent event) {
        escrowService.hold(event.orderNo(), event.amount(), event.approvedAt());
    }

    @ApplicationModuleListener
    void onCanceled(PaymentCanceledEvent event) {
        // 전액 취소만 홀드를 환불한다. 부분취소는 잔여 결제가 살아 있으므로 홀드를 HELD로 유지한다.
        if (event.fullyCanceled()) {
            escrowService.refundIfHeld(event.orderNo());
        }
    }
}
