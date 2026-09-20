package com.beomsu.becommerce.ledger.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 분개 한 줄 — append-only. 절대 UPDATE/DELETE 하지 않는다.
 *
 * <p>금액({@code amount})은 항상 양수이고, 부호는 {@link EntryDirection}으로 표현한다.
 * 음수를 허용하면 방향과 이중 표현이 되어 버그의 온상이 되기 때문이다.
 */
@Entity
@Table(name = "ledger_entries")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transaction_id", nullable = false)
    private LedgerTransaction transaction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AccountType account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 6)
    private EntryDirection direction;

    @Column(nullable = false)
    private long amount;

    private LedgerEntry(AccountType account, EntryDirection direction, long amount) {
        if (amount <= 0) {
            throw new IllegalArgumentException("분개 금액은 양수여야 합니다: " + amount);
        }
        this.account = account;
        this.direction = direction;
        this.amount = amount;
    }

    static LedgerEntry debit(AccountType account, long amount) {
        return new LedgerEntry(account, EntryDirection.DEBIT, amount);
    }

    static LedgerEntry credit(AccountType account, long amount) {
        return new LedgerEntry(account, EntryDirection.CREDIT, amount);
    }

    void assignTo(LedgerTransaction transaction) {
        this.transaction = transaction;
    }

    long signedAmount() {
        return direction == EntryDirection.DEBIT ? amount : -amount;
    }
}
