package com.beomsu.becommerce.shorts;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 업로드 완료({@link ShortUploadedEvent})를 받아 {@link ShortsTranscodeService#processUploaded}로
 * 변환 파이프라인을 깨우는 Outbox 리스너(R23). Outbox(Event Publication Registry, ADR-002와
 * 같은 구조)가 유실을 막고, 원래 트랜잭션 커밋 후 별도 스레드에서 돈다({@code @ApplicationModuleListener}).
 *
 * <p>{@code app.shorts.transcode.enabled=true}일 때만 빈으로 등록된다 — worker 프로파일에서만
 * 켜고(명세 5절의 Kafka+FFmpeg 워커 대신 같은 jar의 프로파일을 고른 이유는 ADR 참고) API
 * 배포에는 이 리스너가 없다({@code EscrowAutoReleaseScheduler}와 같은 게이트 방식). 테스트는
 * 이 프로퍼티를 직접 켜서 리스너가 실제로 동작을 트리거하는지 확인한다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.shorts.transcode.enabled", havingValue = "true")
class ShortsTranscodeListener {

    private final ShortsTranscodeService transcodeService;

    @ApplicationModuleListener
    void onUploaded(ShortUploadedEvent event) {
        transcodeService.processUploaded(event.shortVideoId());
    }
}
