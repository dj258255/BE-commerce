package com.beomsu.becommerce.order.compensation;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

interface CompensationTaskRepository extends JpaRepository<CompensationTask, Long> {

    /** 재시도 도래분 — 주어진 상태이면서 nextAttemptAt이 임계 시각 이전인 태스크. */
    List<CompensationTask> findByStatusAndNextAttemptAtBefore(CompensationStatus status, Instant threshold, Pageable page);

    /** 어드민 관측용 — 상태별 태스크 페이지(운영이 FAILED 소진 건을 조회). 전건 로딩 방지 위해 페이지 단위. */
    Page<CompensationTask> findByStatus(CompensationStatus status, Pageable pageable);

    /** 관측용 — 주어진 상태에서 가장 오래된 태스크의 생성 시각. 적체 나이 게이지의 소스. 없으면 empty. */
    @Query("select min(t.createdAt) from CompensationTask t where t.status = :status")
    Optional<Instant> findOldestCreatedAt(@Param("status") CompensationStatus status);
}
