package com.beomsu.becommerce.shorts;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ShortVideoRepository extends JpaRepository<ShortVideo, Long> {

    /** 피드 첫 쪽(R26) — 커서 없음. id 내림차순(=최신순, IDENTITY라 생성 순서와 같다). */
    List<ShortVideo> findByStatusOrderByIdDesc(ShortVideoStatus status, Pageable pageable);

    /** 피드 다음 쪽(R26) — 커서(마지막으로 받은 id)보다 작은 것만, 역시 id 내림차순. */
    List<ShortVideo> findByStatusAndIdLessThanOrderByIdDesc(
            ShortVideoStatus status, long cursor, Pageable pageable);

    /**
     * 조건부 UPDATE로 상태를 넘겨받는다(R23) — {@code sources}에 있을 때만 {@code target}으로
     * 바뀌고, 영향받은 행 수를 돌려준다. 두 워커(또는 같은 이벤트의 중복 전달)가 동시에 같은
     * 영상을 집어도 WHERE의 상태 조건이 DB 쪽에서 원자적으로 걸러, **하나만 1을 받고 나머지는
     * 0을 받는다** — {@code StockReservationRepository}·{@code IdempotencyRepository}와 같은
     * "조건부 UPDATE" 관례. 0이면 호출자는 "다른 워커가 이미 처리 중"으로 보고 조용히 물러난다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("update ShortVideo v set v.status = :target, v.updatedAt = :now where v.id = :id and v.status in :sources")
    int claimTransition(@Param("id") long id, @Param("sources") Collection<ShortVideoStatus> sources,
                         @Param("target") ShortVideoStatus target, @Param("now") Instant now);
}
