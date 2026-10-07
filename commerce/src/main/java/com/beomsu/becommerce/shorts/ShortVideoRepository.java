package com.beomsu.becommerce.shorts;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ShortVideoRepository extends JpaRepository<ShortVideo, Long> {

    /** 피드 첫 쪽(R26) — 커서 없음. id 내림차순(=최신순, IDENTITY라 생성 순서와 같다). */
    List<ShortVideo> findByStatusOrderByIdDesc(ShortVideoStatus status, Pageable pageable);

    /** 피드 다음 쪽(R26) — 커서(마지막으로 받은 id)보다 작은 것만, 역시 id 내림차순. */
    List<ShortVideo> findByStatusAndIdLessThanOrderByIdDesc(
            ShortVideoStatus status, long cursor, Pageable pageable);
}
