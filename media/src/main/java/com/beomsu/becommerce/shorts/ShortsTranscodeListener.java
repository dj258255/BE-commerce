package com.beomsu.becommerce.shorts;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업로드 완료({@link ShortUploadedEvent})를 받아 {@link ShortsTranscodeService#processUploaded}로
 * 변환 파이프라인을 깨우는 Outbox 리스너(R23). Outbox(Event Publication Registry, ADR-002와
 * 같은 구조)가 유실을 막고, 원래 트랜잭션 커밋 후 별도 스레드에서 돈다({@code @ApplicationModuleListener}).
 *
 * <p><b>{@code @Transactional(propagation = NOT_SUPPORTED)}이 필수다.</b>
 * {@code @ApplicationModuleListener}는 {@code @Async} + {@code @Transactional(REQUIRES_NEW)}
 * + {@code @TransactionalEventListener}를 합성한 애너테이션이다(직접 확인:
 * {@code javap -v}로 클래스 파일을 열어 세 애너테이션이 들어 있는 것을 봤다) — 즉 이 메서드는
 * 기본적으로 **새 트랜잭션에 들어간 채로** 시작된다. 그 상태에서 바로
 * {@link ShortsTranscodeService#processUploaded}(FFmpeg 변환, 수 초~수십 초)를 부르면,
 * {@link ShortVideoTransitionService}의 "짧은 트랜잭션" 메서드들(기본 전파 REQUIRED)이 전부
 * 그 바깥 트랜잭션에 **합류**해 버려 — 결국 하나의 긴 트랜잭션이 된다. 직접 겪은 증상: probe·
 * transcode가 끝날 때까지 DB에 아무것도 안 보이다가 맨 끝에 한꺼번에 커밋됐다(PROBING·
 * TRANSCODING 전이가 조회 API·심지어 완전히 새 JDBC 커넥션으로 직접 쿼리해도 안 보였다).
 * 여기서 {@code NOT_SUPPORTED}로 그 바깥 트랜잭션을 꺼야(suspend) {@link ShortVideoTransitionService}의
 * 메서드들이 진짜로 독립된 짧은 트랜잭션을 연다.
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
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void onUploaded(ShortUploadedEvent event) {
        transcodeService.processUploaded(event.shortVideoId());
    }
}
