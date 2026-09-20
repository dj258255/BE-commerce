package com.beomsu.becommerce.ledger.internal;

import java.time.Instant;
import java.util.List;

/**
 * 원장 트랜잭션 조회 뷰 — 감사용 어드민 화면({@code GET /api/v1/admin/ledger}).
 *
 * <p>복식부기 트랜잭션 한 건을 분개 목록과 함께 노출한다. {@code balanced}는 차변·대변 균형
 * ({@link LedgerTransaction#imbalance()}==0) 여부로, 감사자가 정합 위반을 한눈에 볼 수 있게 한다.
 * 엔티티(package-private) 대신 이 record로만 경계를 넘긴다.
 */
public record LedgerView(Long id, String txType, String sourceType, long sourceId,
                         String description, boolean balanced, Instant createdAt,
                         List<EntryView> entries) {

    /** 분개 한 줄 — 계정·방향(DEBIT/CREDIT)·금액(항상 양수). */
    public record EntryView(String account, String direction, long amount) {
        static EntryView from(LedgerEntry e) {
            return new EntryView(e.getAccount().name(), e.getDirection().name(), e.getAmount());
        }
    }

    static LedgerView from(LedgerTransaction tx) {
        return new LedgerView(tx.getId(), tx.getTxType(), tx.getSourceType(), tx.getSourceId(),
                tx.getDescription(), tx.imbalance() == 0, tx.getCreatedAt(),
                tx.getEntries().stream().map(EntryView::from).toList());
    }
}
