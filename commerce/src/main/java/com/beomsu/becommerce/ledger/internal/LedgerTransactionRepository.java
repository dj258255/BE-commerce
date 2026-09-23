package com.beomsu.becommerce.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    boolean existsByTxTypeAndSourceTypeAndSourceIdAndSourceSeq(
            String txType, String sourceType, long sourceId, int sourceSeq);

    /** 최근 원장 트랜잭션 — 감사용. Top50으로 상한. */
    List<LedgerTransaction> findTop50ByOrderByIdDesc();

    /** 타임라인 조립용(ADR-011). 원장은 주문번호가 아니라 원천(결제 등)으로 묶인다. */
    List<LedgerTransaction> findBySourceTypeAndSourceIdOrderByIdAsc(String sourceType, long sourceId);
}
