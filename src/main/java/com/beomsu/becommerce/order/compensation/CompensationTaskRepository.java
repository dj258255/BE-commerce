package com.beomsu.becommerce.order.compensation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

interface CompensationTaskRepository extends JpaRepository<CompensationTask, Long> {

    /** 재시도 도래분 — 주어진 상태이면서 nextAttemptAt이 임계 시각 이전인 태스크. */
    List<CompensationTask> findByStatusAndNextAttemptAtBefore(CompensationStatus status, Instant threshold, Pageable page);

    /** 어드민 관측용 — 상태별 태스크 페이지(운영이 FAILED 소진 건을 조회). 전건 로딩 방지 위해 페이지 단위. */
    Page<CompensationTask> findByStatus(CompensationStatus status, Pageable pageable);
}
