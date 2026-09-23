package com.beomsu.becommerce.ledger.internal;

import com.beomsu.becommerce.ledger.internal.LedgerTransaction;
import com.beomsu.becommerce.ledger.internal.LedgerEntry;
import com.beomsu.becommerce.ledger.internal.AccountType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerTransactionTest {

    @Test
    @DisplayName("차변 합계 = 대변 합계면 거래가 만들어지고 균형이 0이다")
    void balancedTransaction() {
        LedgerTransaction tx = LedgerTransaction.of(
                "PAYMENT_APPROVED", "PAYMENT", 1L, "결제 승인",
                List.of(
                        LedgerEntry.debit(AccountType.PG_RECEIVABLE, 10_000),
                        LedgerEntry.credit(AccountType.SALES, 10_000)
                ));

        assertThat(tx.imbalance()).isZero();
        assertThat(tx.getEntries()).hasSize(2);
    }

    @Test
    @DisplayName("차변 합계 ≠ 대변 합계면 거래를 만들 수 없다 — 불균형은 존재 불가")
    void unbalancedTransactionRejected() {
        assertThatThrownBy(() -> LedgerTransaction.of(
                "BAD", "PAYMENT", 1L, "불균형",
                List.of(
                        LedgerEntry.debit(AccountType.PG_RECEIVABLE, 10_000),
                        LedgerEntry.credit(AccountType.SALES, 9_000)   // 1,000 부족
                )))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("차변 합계 ≠ 대변 합계");
    }

    @Test
    @DisplayName("여러 분개로 나뉘어도 합계가 맞으면 균형이다")
    void multiEntryBalanced() {
        LedgerTransaction tx = LedgerTransaction.of(
                "SPLIT", "PAYMENT", 2L, "분할",
                List.of(
                        LedgerEntry.debit(AccountType.PG_RECEIVABLE, 7_000),
                        LedgerEntry.debit(AccountType.PG_RECEIVABLE, 3_000),
                        LedgerEntry.credit(AccountType.SALES, 10_000)
                ));

        assertThat(tx.imbalance()).isZero();
    }

    @Test
    @DisplayName("분개 금액은 음수일 수 없다 (부호는 방향으로 표현)")
    void entryAmountMustBePositive() {
        assertThatThrownBy(() -> LedgerEntry.debit(AccountType.SALES, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
