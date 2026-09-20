package com.beomsu.becommerce.ledger.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * 분개(entry) 조회 — 잔액 파생의 근거.
 *
 * <p>{@code ledger/package-info} 가 "잔액은 엔트리의 합으로 파생된다"고 선언만 하고, 그 선언을
 * 쓰는 쿼리가 아무 데도 없었다. 계정별 잔액이 여기서 처음 계산된다.
 *
 * <p>부호는 {@code direction} 으로 표현한다(금액은 항상 양수). 차변이면 +, 대변이면 −. 그래야
 * {@code SUM(signed_amount)} 하나로 계정 잔액이 나온다.
 */
// 같은 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다(LedgerEntry 와 같은 이유로 public).
public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** 계정별 잔액 한 줄 — JPQL 별칭과 이름이 맞는 닫힌 프로젝션. */
    interface AccountBalanceRow {
        AccountType getAccount();

        long getBalance();
    }

    /**
     * 모든 계정의 잔액. <b>전체 행을 훑으므로 O(N)</b> 이다 — 인덱스는 훑는 폭을 줄일 뿐 합계 자체를
     * 상수 시간으로 만들지 않는다. 그래서 잔액 조회의 비용은 원장 행 수에 비례하고, 그 한계가
     * 스냅샷이 필요해지는 지점을 정한다(ADR-025).
     */
    @Query("""
            select e.account as account,
                   coalesce(sum(case when e.direction = com.beomsu.becommerce.ledger.internal.EntryDirection.DEBIT
                                     then e.amount else -e.amount end), 0) as balance
            from LedgerEntry e
            group by e.account
            """)
    List<AccountBalanceRow> sumByAccount();

    /** 한 계정의 잔액. 인덱스가 있으면 그 계정의 분개만 훑는다(범위 스캔). */
    @Query("""
            select coalesce(sum(case when e.direction = com.beomsu.becommerce.ledger.internal.EntryDirection.DEBIT
                                     then e.amount else -e.amount end), 0)
            from LedgerEntry e
            where e.account = :account
            """)
    long sumByAccount(@Param("account") AccountType account);
}
