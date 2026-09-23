package com.beomsu.becommerce.home.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

interface HomeImpressionRepository extends JpaRepository<HomeImpression, Long> {

    /** 이 사용자의 노출 기록 — 최근 것부터. 조회 동선이 사용자 기준이라 인덱스도 그 모양이다. */
    List<HomeImpression> findByUserIdOrderByIdDesc(long userId);

    /**
     * 보존 기간이 지난 행 — <b>배치 단위로만</b> 가져온다.
     *
     * <p>한 번에 전부 지우지 않는 이유: 260만 행을 한 트랜잭션에 지우면 그만큼 긴 잠금과 큰 롤백
     * 세그먼트가 생긴다. 배치로 나누면 정리 작업이 홈 조회를 막지 않는다(정리는 관측이고 홈은 제품이다).
     */
    List<HomeImpression> findByCreatedAtBeforeOrderByIdAsc(Instant cutoff, Pageable pageable);
}
