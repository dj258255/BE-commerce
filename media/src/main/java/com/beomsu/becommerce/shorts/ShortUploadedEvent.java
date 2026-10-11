package com.beomsu.becommerce.shorts;

/**
 * 업로드 완료 알림(R23) — {@link ShortsService#completeUpload}가 같은 트랜잭션에서 발행하면
 * Spring Modulith가 Outbox(event_publication 테이블, ADR-002와 같은 구조)에 적재한다. 커밋
 * 후 {@link ShortsTranscodeListener}가 받아 변환 파이프라인을 깨운다.
 */
public record ShortUploadedEvent(long shortVideoId) {
}
