package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.escrow.EscrowReleasedEvent;
import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.LocalDate;


/**
 * 결제·에스크로 사건을 정산에 반영하는 리스너 — 정산을 에스크로 생명주기에 정렬한다.
 *
 * <p>{@code @ApplicationModuleListener}로 세 이벤트를 받는다:
 * <ul>
 *   <li>{@link PaymentConfirmedEvent} → 정산 항목을 PENDING_CONFIRMATION(구매확정 대기)으로 적재.
 *   <li>{@link EscrowReleasedEvent} → 구매확정 시 항목을 CONFIRMED(정산 가능)로 전이.
 *       <b>이전까지 구독자 0의 죽은 이벤트였던 것을 살려, 정산을 승인 시점이 아니라 구매확정 시점으로 옮긴다.</b>
 *   <li>{@link PaymentCanceledEvent} → 취소분을 정산에서 제외/차감(전액→CANCELED, 부분→금액 차감).
 * </ul>
 *
 * <p>Modulith 이벤트 레지스트리(=Outbox)가 유실을 막고, 서비스가 상태 가드로 멱등하게 반영한다
 * (at-least-once 재전달·순서 역전 대비).
 */
@Component
@RequiredArgsConstructor
class PaymentSettlementListener {

    private final SettlementService settlementService;

    @ApplicationModuleListener
    void onConfirmed(PaymentConfirmedEvent event) {
        settlementService.registerConfirmedPayment(event);
    }

    @ApplicationModuleListener
    void onEscrowReleased(EscrowReleasedEvent event) {
        // 릴리스 시각의 KST 날짜를 집계 기준일로 넘긴다 — 정산은 승인일이 아니라 구매확정일 기준.
        LocalDate releaseDate = LocalDate.ofInstant(event.releasedAt(), SettlementService.SETTLEMENT_ZONE);
        settlementService.confirmSettlement(event.orderNo(), releaseDate);
    }

    @ApplicationModuleListener
    void onCanceled(PaymentCanceledEvent event) {
        settlementService.reflectCancellation(event);
    }
}
