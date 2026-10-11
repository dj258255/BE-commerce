package com.beomsu.becommerce.live;

import java.time.Instant;

/**
 * 송출 종료 알림(R3, 명세의 {@code live.ended}) — 재접속 유예(기본 30초)를 넘겨
 * {@link LiveBroadcastGraceScheduler}가 발행한다. {@link LiveStartedEvent}와 같은 이유로
 * Outbox에서 시작한다(ADR-082).
 *
 * <p>{@code occurredAt}은 R3 인수 조건이 요구하는 서버 시각 — {@link LiveBroadcast#getEndedAt()}
 * 과 같은 값이다.
 */
public record LiveEndedEvent(long broadcastId, Instant occurredAt) {
}
