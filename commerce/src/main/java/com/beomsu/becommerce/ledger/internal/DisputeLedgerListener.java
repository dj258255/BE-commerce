package com.beomsu.becommerce.ledger.internal;

import com.beomsu.becommerce.dispute.DisputeLostEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 분쟁 패소를 원장에 반영하는 리스너.
 *
 * <p>{@code @ApplicationModuleListener}로 {@link DisputeLostEvent}를 받아 원매출을 역분개한다.
 * {@link PaymentLedgerListener}와 같은 패턴 — Modulith 아웃박스가 유실을 막고, 원장 서비스가
 * (disputeId 유니크로) 멱등하게 기록한다. dispute는 ledger를 모르고 이벤트만 발행한다(순환 없음).
 */
@Component
@RequiredArgsConstructor
class DisputeLedgerListener {

    private final LedgerService ledgerService;

    @ApplicationModuleListener
    void onDisputeLost(DisputeLostEvent event) {
        ledgerService.recordDisputeLost(event);
    }
}
