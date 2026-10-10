package com.beomsu.becommerce.shorts;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 업로드 완료 후 PROBING → TRANSCODING → READY(또는 QUARANTINED)로 끌고 가는 변환 파이프라인
 * 오케스트레이터(R23). {@link ShortsTranscodeListener}(worker 프로파일에서만 켜지는 Outbox
 * 리스너)가 {@link #processUploaded}를 호출한다.
 *
 * <p><b>이 클래스는 {@code @Transactional}이 아니다.</b> FFmpeg 변환(수 초~수십 초)을 DB
 * 트랜잭션 밖에서 돌리기 위해서다 — 전부를 한 트랜잭션으로 감싸면 그 시간만큼 DB 커넥션을
 * 붙잡아, 변환이 동시에 몇 건만 겹쳐도 커넥션 풀이 말라 무관한 주문·결제 요청까지 막힌다.
 * 상태 전이(PROBING·TRANSCODING·READY/FAILED/QUARANTINED)는 전부
 * {@link ShortVideoTransitionService}(서로 다른 빈)의 짧은 {@code @Transactional} 메서드가
 * 맡는다 — {@link #processUploaded}가 부르는 {@link TranscodeRunner#probe}·
 * {@link TranscodeRunner#transcode} 호출 중에는 활성 트랜잭션이 없다.
 *
 * <p>재시도는 {@link #processUploaded} 안의 루프가 맡는다 — 실패하면
 * {@link ShortVideoTransitionService#recordFailure}가 FAILED(또는 재시도 소진 시
 * QUARANTINED)를 커밋하고 돌려주고, FAILED면 {@link ShortVideoTransitionService#claimForProbing}부터
 * 다시 돈다(가짜 실행기는 지연이 없어 즉시 재시도해도 안전하다 — 실제 FFmpeg도 재시도 사이
 * 지연을 아직 두지 않는다, ADR-081 현행화 참고).
 */
@Service
@RequiredArgsConstructor
public class ShortsTranscodeService {

    private static final Logger log = LoggerFactory.getLogger(ShortsTranscodeService.class);

    private final ShortVideoTransitionService transitions;
    private final TranscodeRunner transcodeRunner;

    public void processUploaded(long shortVideoId) {
        while (true) {
            if (!transitions.claimForProbing(shortVideoId)) {
                log.debug("숏폼 변환 claim 실패(다른 워커가 처리 중이거나 더는 처리할 상태가 아님) id={}",
                        shortVideoId);
                return;
            }

            String objectKey = transitions.objectKeyOf(shortVideoId);
            TranscodeRunner.ProbeResult probe = transcodeRunner.probe(objectKey);
            if (!probe.success()) {
                if (afterFailure(shortVideoId, probe.failureReason())) {
                    return;
                }
                continue;
            }

            if (!transitions.claimForTranscoding(shortVideoId)) {
                log.warn("숏폼 변환: PROBING→TRANSCODING claim 실패(예상 밖 상황) id={}", shortVideoId);
                return;
            }

            TranscodeRunner.TranscodeResult result = transcodeRunner.transcode(objectKey);
            if (!result.success()) {
                if (afterFailure(shortVideoId, result.failureReason())) {
                    return;
                }
                continue;
            }

            ShortVideoStatus status = transitions.recordTranscodeResult(shortVideoId, result.output());
            if (status == ShortVideoStatus.FAILED) {
                continue; // R23.2: 산출물 불완전 — 재시도
            }
            logIfQuarantined(shortVideoId, status);
            return; // READY 또는 QUARANTINED
        }
    }

    /** 실패를 커밋하고, 멈춰야 하면(QUARANTINED) true, 재시도해야 하면(FAILED) false. */
    private boolean afterFailure(long shortVideoId, String reason) {
        ShortVideoStatus status = transitions.recordFailure(shortVideoId, reason);
        logIfQuarantined(shortVideoId, status);
        return status == ShortVideoStatus.QUARANTINED;
    }

    private void logIfQuarantined(long shortVideoId, ShortVideoStatus status) {
        if (status == ShortVideoStatus.QUARANTINED) {
            log.warn("숏폼 변환 격리(재시도 소진) id={}", shortVideoId);
        }
    }
}
