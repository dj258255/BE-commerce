package com.beomsu.pay.notification.consumption;

import java.time.Instant;

/**
 * DLQ 복구 상태 요약 — 격리(발견)의 표면(ADR-030).
 *
 * @param pendingCount    아직 복구되지 않은 격리 건수
 * @param oldestCreatedAt 가장 오래 기다린 격리 건의 생성 시각(없으면 null)
 */
public record DeadLetterSummary(long pendingCount, Instant oldestCreatedAt) {
}
