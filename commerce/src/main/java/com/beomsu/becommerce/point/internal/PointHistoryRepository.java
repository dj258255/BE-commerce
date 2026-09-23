package com.beomsu.becommerce.point.internal;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

// 같은 모듈의 API(루트)가 참조하므로 public 이다. Modulith 문서가 짚듯 internal 에 있어도
// public 이면 컴파일러는 막지 못하며, 모듈 밖 접근은 ModularityTests 의 allowedDependencies 가 막는다.
public interface PointHistoryRepository extends JpaRepository<PointHistory, Long> {

    /** 멱등 판정: 같은 주문에 대해 같은 유형(USE/RESTORE/EARN) 이력이 이미 있는지. */
    boolean existsByOrderNoAndType(String orderNo, PointHistoryType type);

    /** 최근 포인트 이력 — 최신순. 잔액 조회 화면에 함께 싣는다. */
    List<PointHistory> findTop20ByUserIdOrderByIdDesc(long userId);

    /** 타임라인 조립용(ADR-011). 한 주문이 만든 포인트 사건 전부 — 사용·복원·적립·회수. */
    List<PointHistory> findByOrderNoOrderByIdAsc(String orderNo);

    /** 같은 주문·유형의 금액 합계. 이력이 없으면 0. 환불 가능 포인트 계산에 쓴다. */
    @Query("select coalesce(sum(h.amount),0) from PointHistory h where h.orderNo = :orderNo and h.type = :type")
    long sumAmountByOrderNoAndType(@Param("orderNo") String orderNo, @Param("type") PointHistoryType type);
}
