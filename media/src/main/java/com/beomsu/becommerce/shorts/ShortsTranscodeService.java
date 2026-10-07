package com.beomsu.becommerce.shorts;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 업로드 완료 후 PROBING → TRANSCODING → READY(또는 QUARANTINED)로 끌고 가는 변환 파이프라인
 * 본체(R23). {@link ShortsTranscodeListener}(worker 프로파일에서만 켜지는 Outbox 리스너)가
 * {@link #processUploaded}를 호출한다.
 *
 * <p>이 서비스 자체는 프로파일과 무관하게 항상 빈으로 존재한다 — 켜고 끄는 책임은 리스너 쪽
 * {@code @ConditionalOnProperty} 하나에만 두어, 이 서비스는 프로퍼티를 몰라도 되고 테스트도
 * 프로퍼티를 켜지 않고 바로 호출해 볼 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional
public class ShortsTranscodeService {

    private static final Logger log = LoggerFactory.getLogger(ShortsTranscodeService.class);

    private final ShortVideoRepository repository;
    private final TranscodeRunner transcodeRunner;

    /**
     * UPLOADED 상태의 영상 하나를 READY 또는 QUARANTINED까지 끌고 간다. probe·transcode가
     * 실패하면 {@link ShortVideo#fail}이 FAILED로 남기고, 재시도 소진(3회) 전까지는 같은 호출
     * 안에서 다시 PROBING에 들어가 재시도한다(가짜 실행기는 지연이 없어 즉시 재시도해도
     * 안전하다 — 실제 FFmpeg로 바뀌면 재시도 간 지연을 둘지 다시 본다).
     */
    public void processUploaded(long shortVideoId) {
        ShortVideo video = repository.findById(shortVideoId)
                .orElseThrow(() -> ShortsException.notFound(shortVideoId));

        video.startProbing();
        attempt(video);
        while (video.getStatus() == ShortVideoStatus.FAILED) {
            video.startProbing();
            attempt(video);
        }

        if (video.isQuarantined()) {
            log.warn("숏폼 변환 격리(재시도 소진) id={} reason={}", video.getId(), video.getFailureReason());
        }
    }

    private void attempt(ShortVideo video) {
        TranscodeRunner.ProbeResult probe = transcodeRunner.probe(video.getObjectKey());
        if (!probe.success()) {
            video.fail(probe.failureReason());
            return;
        }
        video.startTranscoding();
        TranscodeRunner.TranscodeResult result = transcodeRunner.transcode(video.getObjectKey());
        if (!result.success()) {
            video.fail(result.failureReason());
            return;
        }
        video.completeTranscoding(result.output());
    }
}
