package com.beomsu.becommerce.live;

import java.time.Instant;

/**
 * 송출 시작 알림(R3, 명세의 {@code live.started}) — {@link LiveBroadcastService}가 같은
 * 트랜잭션에서 발행하면 Spring Modulith가 Outbox(event_publication, ADR-002와 같은 구조)에
 * 적재한다. 명세 5절은 이 이벤트를 Kafka로 내보내라고 적었지만, R23(숏폼)과 같은 이유로
 * Outbox(인프로세스)에서 시작한다 — 근거는 ADR-082.
 *
 * <p>{@code occurredAt}은 R3 인수 조건("이벤트에는 방송 id와 서버 시각이 포함된다")이 요구하는
 * 서버 시각이다 — {@link LiveBroadcast#getStartedAt()}과 같은 값(전이 시각)을 그대로 싣는다.
 */
public record LiveStartedEvent(long broadcastId, Instant occurredAt) {
}
