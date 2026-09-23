package com.beomsu.becommerce.assist.narrative;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface NarrativeAuditRepository extends JpaRepository<NarrativeAudit, Long> {

    List<NarrativeAudit> findByOrderNoOrderByCreatedAtDesc(String orderNo);
}
