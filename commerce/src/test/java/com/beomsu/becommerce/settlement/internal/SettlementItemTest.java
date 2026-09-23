package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.settlement.internal.SettlementItemStatus;
import com.beomsu.becommerce.settlement.internal.SettlementItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class SettlementItemTest {

    private static final LocalDate DATE = LocalDate.of(2026, 7, 5);
    private static final long PLATFORM = com.beomsu.becommerce.seller.SellerPayoutGate.PLATFORM_SELLER_ID;

    private static SettlementItem newItem() {
        return SettlementItem.of(1L, "order-1", 10_000, DATE, PLATFORM);
    }

    @Test
    @DisplayName("팩토리는 PENDING_CONFIRMATION(구매확정 대기)으로 생성한다")
    void factoryStartsPendingConfirmation() {
        assertThat(newItem().getStatus()).isEqualTo(SettlementItemStatus.PENDING_CONFIRMATION);
    }

    @Test
    @DisplayName("confirm: PENDING_CONFIRMATION → CONFIRMED, confirmedDate를 릴리스일로 재스탬프")
    void confirmTransitions() {
        SettlementItem item = newItem(); // 적재 시 confirmedDate = DATE(승인일 placeholder)
        LocalDate releaseDate = DATE.plusDays(7);
        item.confirm(releaseDate);
        assertThat(item.getStatus()).isEqualTo(SettlementItemStatus.CONFIRMED);
        // 집계 기준일이 승인일이 아니라 릴리스일로 재스탬프돼야 한다(다일 에스크로 홀드 대응).
        assertThat(item.getConfirmedDate()).isEqualTo(releaseDate);
    }

    @Test
    @DisplayName("confirm 멱등: CONFIRMED/SETTLED/CANCELED에서는 무시")
    void confirmIsGuarded() {
        SettlementItem settled = newItem();
        settled.confirm(DATE);
        settled.markSettled(1L);
        settled.confirm(DATE);
        assertThat(settled.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);

        SettlementItem canceled = newItem();
        canceled.cancel();
        canceled.confirm(DATE);
        assertThat(canceled.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);
    }

    @Test
    @DisplayName("markSettled: CONFIRMED에서만 SETTLED로 전이")
    void markSettledOnlyFromConfirmed() {
        SettlementItem fromPending = newItem();
        fromPending.markSettled(1L); // PENDING_CONFIRMATION에서는 무시
        assertThat(fromPending.getStatus()).isEqualTo(SettlementItemStatus.PENDING_CONFIRMATION);

        SettlementItem confirmed = newItem();
        confirmed.confirm(DATE);
        confirmed.markSettled(1L);
        assertThat(confirmed.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("cancel: PENDING_CONFIRMATION/CONFIRMED → CANCELED, SETTLED/CANCELED는 무시")
    void cancelGuards() {
        SettlementItem fromPending = newItem();
        fromPending.cancel();
        assertThat(fromPending.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);

        SettlementItem fromConfirmed = newItem();
        fromConfirmed.confirm(DATE);
        fromConfirmed.cancel();
        assertThat(fromConfirmed.getStatus()).isEqualTo(SettlementItemStatus.CANCELED);

        SettlementItem settled = newItem();
        settled.confirm(DATE);
        settled.markSettled(1L);
        settled.cancel(); // SETTLED는 취소로 되돌리지 않는다
        assertThat(settled.getStatus()).isEqualTo(SettlementItemStatus.SETTLED);
    }

    @Test
    @DisplayName("applySettleableBalance: 금액을 잔액(절대값)으로 세팅 — 같은 취소 재배달은 멱등")
    void applySettleableBalanceSetsAbsolute() {
        SettlementItem item = newItem(); // amount 10,000
        item.applySettleableBalance(0, 7_000);
        assertThat(item.getAmount()).isEqualTo(7_000);

        // 멱등: 같은 취소가 다시 와도 그대로(델타 차감이 아님)
        item.applySettleableBalance(0, 7_000);
        assertThat(item.getAmount()).isEqualTo(7_000);

        // 방어: 음수 잔액은 0으로 바닥 처리
        item.applySettleableBalance(1, -1);
        assertThat(item.getAmount()).isEqualTo(0);
    }

    @Test
    @DisplayName("순서가 역전돼 도착한 옛 취소는 잔액을 되돌리지 못한다")
    void olderCancellationDoesNotRaiseBalanceBack() {
        SettlementItem item = newItem(); // amount 10,000

        // 2차 취소가 먼저 소비됐다 — 잔액 4,000
        item.applySettleableBalance(1, 4_000);
        assertThat(item.getAmount()).isEqualTo(4_000);

        // 늦게 도착한 1차 취소(잔액 7,000). 절대값 세팅만 있으면 4,000이 7,000으로 <되돌아간다>.
        item.applySettleableBalance(0, 7_000);
        assertThat(item.getAmount())
                .as("절대값 세팅은 중복에는 멱등이지만 순서 역전에는 안전하지 않다")
                .isEqualTo(4_000);
    }
}
