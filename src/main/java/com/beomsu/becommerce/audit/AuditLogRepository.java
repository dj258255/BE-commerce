package com.beomsu.becommerce.audit;

import org.springframework.data.jpa.repository.JpaRepository;

interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    /** 타임라인 조립용(ADR-011). 감사로그는 (targetType, targetId)로 묶인다 — 주문번호가 아니다. */
    java.util.List<AuditLog> findByTargetTypeAndTargetIdOrderByIdAsc(String targetType, String targetId);
}
