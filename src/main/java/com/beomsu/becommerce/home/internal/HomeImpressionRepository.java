package com.beomsu.becommerce.home.internal;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

interface HomeImpressionRepository extends JpaRepository<HomeImpression, Long> {

    /** 이 사용자의 노출 기록 — 최근 것부터. 조회 동선이 사용자 기준이라 인덱스도 그 모양이다. */
    List<HomeImpression> findByUserIdOrderByIdDesc(long userId);
}
