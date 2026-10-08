package com.beomsu.becommerce.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface LiveBroadcastRepository extends JpaRepository<LiveBroadcast, Long> {

    /** MediaMTX 훅(R2·R3)이 스트림 키로 방송을 찾는다. */
    Optional<LiveBroadcast> findByStreamKey(String streamKey);

    /**
     * 재접속 유예가 끝났을 수 있는 후보(R3) — LIVE이고 끊긴 적 있는 방송만. 실제로 유예를
     * 넘겼는지(now - disconnectedAt > grace)는 후보를 좁히는 수준으로만 여기서 거르고
     * ({@code disconnectedAt}이 cutoff보다 이전), 정확한 판단은
     * {@link LiveBroadcast#isGraceExpired}(엔티티, 시계 주입)가 한다.
     */
    List<LiveBroadcast> findByStatusAndDisconnectedAtBefore(LiveBroadcastStatus status, Instant cutoff);
}
