package com.beomsu.becommerce.live;

/**
 * 라이브 방송 상태(R1·R3) — SCHEDULED(예약, 아직 송출 안 함) → LIVE(송출 중) → ENDED(종료,
 * 종료 상태).
 *
 * <p>LIVE에서 송출이 끊겨도 바로 ENDED로 가지 않는다 — 재접속 유예(기본 30초, R3) 동안은
 * 여전히 LIVE다({@link LiveBroadcast#getDisconnectedAt()}가 그 유예를 추적한다). 유예를
 * 넘겨야 {@link LiveBroadcastGraceScheduler}가 ENDED로 끝맺는다.
 */
public enum LiveBroadcastStatus {
    SCHEDULED,
    LIVE,
    ENDED
}
