package com.beomsu.becommerce.shorts;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

interface ShortViewEventRepository extends JpaRepository<ShortViewEvent, Long> {

    /** 후보 영상들의 완료율(R28 신호 1) — 신호가 없는 영상은 결과에 나타나지 않는다(호출자가 0으로 보정). */
    @Query("select e.shortVideoId as videoId, avg(case when e.completed = true then 1.0 else 0.0 end) as rate "
            + "from ShortViewEvent e where e.shortVideoId in :videoIds group by e.shortVideoId")
    List<VideoCompletionRate> completionRates(@Param("videoIds") Collection<Long> videoIds);

    /** 로그인 시청자가 최근 관심(완료 또는 상품탭)을 보인 영상 id — 상품 선호 일치(R28 신호 3)의 원천. */
    @Query("select e.shortVideoId from ShortViewEvent e where e.userId = :userId "
            + "and (e.completed = true or e.productTagTapped = true) order by e.occurredAt desc")
    List<Long> recentEngagedVideoIdsByUser(@Param("userId") long userId, Pageable limit);

    /** 비로그인 시청자(익명 식별자 기준)의 같은 신호. */
    @Query("select e.shortVideoId from ShortViewEvent e where e.anonymousId = :anonymousId "
            + "and (e.completed = true or e.productTagTapped = true) order by e.occurredAt desc")
    List<Long> recentEngagedVideoIdsByAnonymousId(@Param("anonymousId") String anonymousId, Pageable limit);

    interface VideoCompletionRate {
        Long getVideoId();

        Double getRate();
    }
}
