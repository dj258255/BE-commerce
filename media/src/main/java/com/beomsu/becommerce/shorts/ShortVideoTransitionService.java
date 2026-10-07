package com.beomsu.becommerce.shorts;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * 숏폼 변환 파이프라인의 상태 전이를 짧은 트랜잭션 단위로 커밋하는 서비스(R23).
 *
 * <p>{@link ShortsTranscodeService}(오케스트레이터)는 이 클래스의 메서드만 부른다 — probe·
 * transcode(FFmpeg, 초 단위)는 오케스트레이터가 **이 클래스 밖에서** 호출한다. 메서드 하나하나가
 * 별도 짧은 DB 트랜잭션이라, FFmpeg가 도는 동안은 커넥션을 붙잡지 않는다(FFmpeg 변환 한 건이
 * DB 커넥션 풀을 10초씩 물고 있다가 주문·결제 요청까지 막히는 문제를 고친다).
 *
 * <p>{@link #claimForProbing}·{@link #claimForTranscoding}은 조건부 UPDATE
 * ({@link ShortVideoRepository#claimTransition})로 전이한다 — 두 워커(또는 Outbox의 중복
 * 전달)가 같은 영상을 동시에 집어도 하나만 성공한다(R23). 지금 워커는 하나뿐이지만 이 보장은
 * 코드로 남긴다.
 */
@Component
@RequiredArgsConstructor
class ShortVideoTransitionService {

    private final ShortVideoRepository repository;

    /** 전이 없이 objectKey만 읽는다 — 짧은 읽기 전용 트랜잭션. */
    @Transactional(readOnly = true)
    public String objectKeyOf(long shortVideoId) {
        return repository.findById(shortVideoId)
                .orElseThrow(() -> ShortsException.notFound(shortVideoId))
                .getObjectKey();
    }

    /**
     * UPLOADED 또는 FAILED(재시도) → PROBING. 조건부 UPDATE가 0을 돌려주면(이미 다른 워커가
     * 가져갔거나 더는 이 전이를 받을 상태가 아님) false — 호출자는 조용히 물러난다.
     */
    @Transactional
    public boolean claimForProbing(long shortVideoId) {
        int updated = repository.claimTransition(shortVideoId,
                List.of(ShortVideoStatus.UPLOADED, ShortVideoStatus.FAILED),
                ShortVideoStatus.PROBING, Instant.now());
        return updated > 0;
    }

    /** PROBING → TRANSCODING. probe가 막 성공한 바로 그 호출만 여기 온다(같은 이유의 방어). */
    @Transactional
    public boolean claimForTranscoding(long shortVideoId) {
        int updated = repository.claimTransition(shortVideoId,
                List.of(ShortVideoStatus.PROBING), ShortVideoStatus.TRANSCODING, Instant.now());
        return updated > 0;
    }

    /**
     * probe 또는 transcode 실패 — {@link ShortVideo#fail}이 FAILED로 남기고, 재시도(3회)를
     * 소진하면 QUARANTINED로 격리한다. 결과 상태를 그대로 돌려줘 오케스트레이터가 재시도할지
     * (FAILED) 멈출지(QUARANTINED)를 판단하게 한다.
     */
    @Transactional
    public ShortVideoStatus recordFailure(long shortVideoId, String reason) {
        ShortVideo video = repository.findById(shortVideoId)
                .orElseThrow(() -> ShortsException.notFound(shortVideoId));
        video.fail(reason);
        return video.getStatus();
    }

    /**
     * 변환 성공 보고 — 산출물을 기록한다. 완전성(R23.2)은
     * {@link ShortVideo#completeTranscoding}이 보고, 하나라도 없으면 이 호출도 결국 FAILED(또는
     * QUARANTINED)를 돌려준다 — READY로 끝나는 건 산출물이 전부 있을 때뿐이다.
     */
    @Transactional
    public ShortVideoStatus recordTranscodeResult(long shortVideoId, TranscodeOutput output) {
        ShortVideo video = repository.findById(shortVideoId)
                .orElseThrow(() -> ShortsException.notFound(shortVideoId));
        video.completeTranscoding(output);
        return video.getStatus();
    }
}
