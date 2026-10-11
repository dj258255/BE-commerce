package com.beomsu.becommerce.shorts;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * R28 개인화 점수 계산을 <b>제한 시간 안에서</b> 시도하고, 실패하거나 느리면 폴백한다(R29).
 *
 * <p><b>제한 시간 150ms, 왜 이 값인가</b>: 이 계산은 네트워크 호출이 아니라 후보 한 쪽
 * (최대 {@code pageSize+1}개, 기본 11개)에 대한 완료율 집계 1회·시청자 이력 조회 1회뿐이다 —
 * {@code personalization/docs/01-architecture.md}가 적은 "online은 지연 제약, 느리면 폴백"
 * 원칙을 그대로 따르되, 숏폼 피드가 커머스 전체 지연 예산(R15 수량 선점 거절 p95 200ms)에
 * 추가로 얹는 비용이라는 점을 고려해 그보다 작은 값을 잡았다. <b>실측값은 아니다</b> — 이
 * 저장소의 다른 지연 숫자들(ADR-037 등)과 같은 "숫자를 꾸미지 말고" 원칙에 따라, 실제 부하
 * 측정으로 근거가 쌓이면 이 값을 다시 본다(docs/shorts-feed-ranking.md "다시 볼 조건").
 *
 * <p>구현은 {@link ShortsRankingSignals#load}를 별도 스레드에서 돌리고 {@code get(timeout)}으로
 * 묶는다 — DB 조회는 인터럽트로 즉시 끊기지 않으므로 느린 스레드가 백그라운드에 남을 수 있지만,
 * <b>호출자(피드 응답)는 제한 시간을 넘기지 않는다</b>는 보장이 더 중요하다(이 메서드의 계약).
 */
@Component
public class ShortsFeedRanker {

    private static final Logger log = LoggerFactory.getLogger(ShortsFeedRanker.class);

    private final ShortsRankingSignals signals;
    private final Clock clock;
    private final long timeoutMs;

    @Autowired
    public ShortsFeedRanker(ShortsRankingSignals signals,
            @Value("${app.shorts.personalization.timeout-ms:150}") long timeoutMs) {
        this(signals, Clock.systemUTC(), timeoutMs);
    }

    ShortsFeedRanker(ShortsRankingSignals signals, Clock clock, long timeoutMs) {
        this.signals = signals;
        this.clock = clock;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 후보를 점수 내림차순으로 재배열한다. {@code candidates}가 0·1개면 재정렬할 것이 없어 바로
     * 돌려준다(폴백 아님). 그 외에는 {@link ShortsRankingSignals#load}를 제한 시간 안에서 기다리고,
     * 실패하거나 시간을 넘기면 <b>원래 순서(최신순 READY)를 그대로</b> 돌려주며 {@code fallback=true}로
     * 알린다(R29) — 피드 호출자는 항상 200을 받는다.
     */
    public Result rank(List<ShortVideo> candidates, ViewerIdentity viewer) {
        if (candidates.size() <= 1) {
            return new Result(candidates, false);
        }
        List<Long> ids = candidates.stream().map(ShortVideo::getId).toList();
        Instant now = clock.instant();
        try {
            List<ShortVideo> ranked = CompletableFuture
                    .supplyAsync(() -> signals.load(ids, viewer))
                    .thenApply(snapshot -> ShortsFeedRanking.rank(candidates, snapshot, now))
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            return new Result(ranked, false);
        } catch (Exception e) {
            log.warn("R29: 숏폼 피드 개인화 점수 계산 실패/지연 — 최신순 READY 목록으로 폴백합니다", e);
            return new Result(candidates, true);
        }
    }

    /** {@code fallback=true}면 {@code items}는 입력 순서(id 내림차순, 최신순) 그대로다. */
    public record Result(List<ShortVideo> items, boolean fallback) {
    }
}
