package com.beomsu.becommerce.ledger.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 원장 거래 — 하나의 자금 이동을 이루는 분개들의 묶음.
 *
 * <p>불변식: <b>차변 합계 = 대변 합계</b>. 생성 시점에 강제하며, 위반하면 예외가 난다.
 * (txType, sourceType, sourceId) 유니크로 같은 원천의 중복 분개를 DB가 차단한다 — 원장의 멱등성.
 */
@Entity
@Table(name = "ledger_transactions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ledger_tx_source",
                columnNames = {"txType", "sourceType", "sourceId", "sourceSeq"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 40)
    private String txType;

    @Column(nullable = false, length = 30)
    private String sourceType;

    @Column(nullable = false)
    private long sourceId;

    /**
     * 같은 원천에서 사건이 여러 번 일어날 때의 순번. 사건이 한 번뿐인 원천(승인·분쟁)은 0이다.
     *
     * <p>취소가 이 필드를 쓰는 이유는 하나다. 한 결제는 여러 번 취소될 수 있는데 원결제 ID만으로
     * 중복을 판정하면 <b>두 번째 부분취소가 "이미 분개함"으로 조용히 사라진다.</b> 각 분개는 차대가
     * 맞으니 자가 검증에도 안 걸리고, 거래가 통째로 빠진 채 매출만 과대계상된다.
     */
    @Column(nullable = false)
    private int sourceSeq;

    @Column(length = 200)
    private String description;

    @Column(nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private final List<LedgerEntry> entries = new ArrayList<>();

    private LedgerTransaction(String txType, String sourceType, long sourceId, int sourceSeq,
                              String description) {
        this.txType = txType;
        this.sourceType = sourceType;
        this.sourceId = sourceId;
        this.sourceSeq = sourceSeq;
        this.description = description;
        this.createdAt = Instant.now();
    }

    /**
     * 분개들로 거래를 만든다. 차변 합계 ≠ 대변 합계면 예외 — 불균형은 만들어질 수 없다.
     */
    static LedgerTransaction of(String txType, String sourceType, long sourceId,
                                String description, List<LedgerEntry> entries) {
        return of(txType, sourceType, sourceId, 0, description, entries);
    }

    /** 같은 원천에서 사건이 여러 번 일어나는 경우(취소 등) — 순번까지 중복 판정 키에 넣는다. */
    static LedgerTransaction of(String txType, String sourceType, long sourceId, int sourceSeq,
                                String description, List<LedgerEntry> entries) {
        if (entries == null || entries.size() < 2) {
            throw new IllegalArgumentException("거래는 최소 2개의 분개가 필요합니다.");
        }
        long debit = entries.stream().filter(e -> e.getDirection() == EntryDirection.DEBIT)
                .mapToLong(LedgerEntry::getAmount).sum();
        long credit = entries.stream().filter(e -> e.getDirection() == EntryDirection.CREDIT)
                .mapToLong(LedgerEntry::getAmount).sum();
        if (debit != credit) {
            throw new IllegalStateException(
                    "차변 합계 ≠ 대변 합계: 차변 %d, 대변 %d".formatted(debit, credit));
        }
        LedgerTransaction tx =
                new LedgerTransaction(txType, sourceType, sourceId, sourceSeq, description);
        for (LedgerEntry entry : entries) {
            entry.assignTo(tx);
            tx.entries.add(entry);
        }
        return tx;
    }

    public List<LedgerEntry> getEntries() {
        return List.copyOf(entries);
    }

    /** 검증용: 이 거래의 차변·대변 균형(항상 0이어야 정합). */
    public long imbalance() {
        return entries.stream().mapToLong(LedgerEntry::signedAmount).sum();
    }
}
