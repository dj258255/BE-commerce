package com.beomsu.becommerce.settlement.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface SettlementAdjustmentRepository extends JpaRepository<SettlementAdjustment, Long> {

    /** 차기 정산이 집어갈 회수 대기분. */
    List<SettlementAdjustment> findByStatus(SettlementAdjustmentStatus status);

    boolean existsByOrderNoAndCancelSeq(String orderNo, int cancelSeq);
}
