package com.beomsu.becommerce.live;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface LiveBroadcastRepository extends JpaRepository<LiveBroadcast, Long> {

    /** {@code MediaMtxPathPoller}(R3)가 매 주기 "지금 LIVE인 방송"과 MediaMTX의 ready 집합을 맞대 본다. */
    List<LiveBroadcast> findByStatus(LiveBroadcastStatus status);

    /**
     * 재접속 유예가 끝났을 수 있는 후보(R3) — LIVE이고 끊긴 적 있는 방송만. 실제로 유예를
     * 넘겼는지(now - disconnectedAt > grace)는 후보를 좁히는 수준으로만 여기서 거르고
     * ({@code disconnectedAt}이 cutoff보다 이전), 정확한 판단은
     * {@link LiveBroadcast#isGraceExpired}(엔티티, 시계 주입)가 한다.
     */
    List<LiveBroadcast> findByStatusAndDisconnectedAtBefore(LiveBroadcastStatus status, Instant cutoff);
}
