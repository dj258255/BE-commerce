package com.beomsu.becommerce.wallet.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface WalletTransactionRepository extends JpaRepository<WalletTransaction, Long> {

    /** 최근 거래 이력(충전·사용·환불) — 최신순. 잔액 조회 화면에 함께 싣는다. */
    List<WalletTransaction> findTop20ByUserIdOrderByIdDesc(long userId);

    /** 주문 단위 멱등 판정 — 같은 주문의 같은 종류(USE/REFUND) 거래가 이미 있는지. */
    boolean existsByOrderNoAndType(String orderNo, WalletTransactionType type);

    /** 주문에 대한 특정 종류 거래 1건(유니크 인덱스로 주문당 최대 1건) — 복구가 예약 차감액을 역산할 때 쓴다. */
    Optional<WalletTransaction> findByOrderNoAndType(String orderNo, WalletTransactionType type);

    /** 타임라인 조립용(ADR-011). 한 주문이 만든 월렛 사건 전부 — 차감·복원·환불. */
    List<WalletTransaction> findByOrderNoOrderByIdAsc(String orderNo);

    /** 같은 주문·유형의 금액 합계. 이력이 없으면 0. 환불 가능 월렛분(USE−REFUND) 계산에 쓴다. */
    @Query("select coalesce(sum(t.amount),0) from WalletTransaction t where t.orderNo = :orderNo and t.type = :type")
    long sumAmountByOrderNoAndType(@Param("orderNo") String orderNo, @Param("type") WalletTransactionType type);
}
