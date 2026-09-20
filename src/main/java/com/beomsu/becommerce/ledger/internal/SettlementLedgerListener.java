package com.beomsu.becommerce.ledger.internal;

import com.beomsu.becommerce.settlement.SettlementPaidOutEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 정산 지급을 원장에 반영하는 리스너 — PG 미수금 회수.
 *
 * <p>이게 없으면 미수금 계정이 단조 증가만 한다. 승인으로 늘고 취소로 줄기는 하지만, PG가 실제로
 * 돈을 보내 주는 사건이 원장에 없어서 계정 잔액이 현실과 영원히 어긋난다.
 */
@Component
@RequiredArgsConstructor
class SettlementLedgerListener {

    private final LedgerService ledgerService;

    @ApplicationModuleListener
    void onPaidOut(SettlementPaidOutEvent event) {
        ledgerService.recordSettlementPaidOut(event);
    }
}
