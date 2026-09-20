package com.beomsu.becommerce.wallet.internal;

import java.util.List;

/**
 * 월렛 조회 뷰 — 잔액 + 최근 거래 이력. 엔티티 대신 노출한다.
 */
public record WalletView(long balance, List<TxView> history) {

    /** 거래 1건 — 종류·금액·거래 직후 잔액. */
    public record TxView(String type, long amount, long balanceAfter) {
        static TxView from(WalletTransaction t) {
            return new TxView(t.getType().name(), t.getAmount(), t.getBalanceAfter());
        }
    }

    public static WalletView of(long balance, List<WalletTransaction> history) {
        return new WalletView(balance, history.stream().map(TxView::from).toList());
    }
}
