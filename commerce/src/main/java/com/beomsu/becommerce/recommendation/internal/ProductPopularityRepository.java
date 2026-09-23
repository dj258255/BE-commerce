package com.beomsu.becommerce.recommendation.internal;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

interface ProductPopularityRepository extends JpaRepository<ProductPopularity, Long> {

    /** 창 하나에서 순위 순 — 서빙 조회의 유일한 모양이고 인덱스도 그 모양이다. */
    List<ProductPopularity> findByWindowKindOrderByRankNoAsc(String windowKind, Pageable pageable);

    /** 그 창이 <b>언제 데이터인지</b> — 화면과 로그가 "요즘"의 기준을 밝힐 수 있게 한다. */
    @Query("select max(p.computedAt) from ProductPopularity p where p.windowKind = :windowKind")
    LocalDate latestComputedAt(@Param("windowKind") String windowKind);
}
