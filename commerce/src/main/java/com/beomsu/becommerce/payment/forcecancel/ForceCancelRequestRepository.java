package com.beomsu.becommerce.payment.forcecancel;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

interface ForceCancelRequestRepository extends JpaRepository<ForceCancelRequest, Long> {

    /** 어드민 목록용 — 상태별 강제취소 요청 페이지(운영이 REQUESTED 미결 건을 조회). 전건 로딩 방지 위해 페이지 단위. */
    Page<ForceCancelRequest> findByStatus(ForceCancelStatus status, Pageable pageable);
}
