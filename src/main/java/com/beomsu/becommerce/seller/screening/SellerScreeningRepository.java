package com.beomsu.becommerce.seller.screening;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SellerScreeningRepository extends JpaRepository<SellerScreening, Long> {

    List<SellerScreening> findBySellerIdOrderByScreenedAtDesc(Long sellerId);

    /** 사람이 아직 안 본 잠재 일치. 이 큐가 밀리면 지급도 같이 밀린다. */
    List<SellerScreening> findByVerdictAndHumanVerdictIsNull(ScreeningVerdict verdict);

    /**
     * 오탐률. <b>사람이 본 것만 분모</b>에 넣는다 — 안 본 건은 맞고 틀림을 모른다.
     * 이 수치가 있어야 임계를 근거로 고칠 수 있다.
     */
    @Query("""
        select coalesce(sum(case when s.humanVerdict = com.beomsu.becommerce.seller.screening.SellerScreening$HumanVerdict.FALSE_POSITIVE then 1 else 0 end), 0)
             , count(s)
        from SellerScreening s
        where s.verdict = :verdict and s.humanVerdict is not null
        """)
    List<Object[]> falsePositiveStats(ScreeningVerdict verdict);
}
