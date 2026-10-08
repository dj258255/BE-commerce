package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R29: 개인화 점수 계산(R28)이 실패하거나 제한 시간을 넘기면 최신순 READY 목록으로 폴백하는지
 * 검증한다. {@link ShortsRankingSignals}(점수 계산이 의존하는 유일한 외부 신호 경계)의 가짜
 * 구현으로 실패·지연을 주입한다 — 실제 DB·실제 네트워크 지연 없이 결정적으로 재현한다.
 */
class ShortsFeedRankerTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-08T00:00:00Z"), ZoneOffset.UTC);
    private static final UploadMeta META = new UploadMeta(20, 1_000_000L, 1080, 1920, "video/mp4");
    /** 테스트가 느려지지 않을 만큼 짧게 잡은 제한 시간 — 실제 값(150ms)의 근거는 ShortsFeedRanker 참고. */
    private static final long TIMEOUT_MS = 50;

    private static ShortVideo video(long id) {
        ShortVideo v = ShortVideo.upload(1L, "shorts/1/" + id, META);
        ReflectionTestUtils.setField(v, "id", id);
        ReflectionTestUtils.setField(v, "createdAt", CLOCK.instant());
        return v;
    }

    @Test
    @DisplayName("R29: 후보가 0·1개면 신호를 조회하지 않고 바로 돌려준다(폴백 아님)")
    void singleOrEmptyCandidateSkipsRanking() {
        ShortsRankingSignals signals = (ids, viewer) -> {
            throw new AssertionError("호출되면 안 된다");
        };
        ShortsFeedRanker ranker = new ShortsFeedRanker(signals, CLOCK, TIMEOUT_MS);

        ShortsFeedRanker.Result empty = ranker.rank(List.of(), null);
        ShortsFeedRanker.Result single = ranker.rank(List.of(video(1L)), null);

        assertThat(empty.fallback()).isFalse();
        assertThat(single.fallback()).isFalse();
    }

    @Test
    @DisplayName("R29: 신호 계산이 예외를 던지면 200에 해당하는 결과를 그대로 돌려주되 "
            + "fallback=true이고 최신순(id 내림차순) 원래 순서를 유지한다")
    void fallsBackWhenSignalsFail() {
        ShortsRankingSignals failing = (ids, viewer) -> {
            throw new RuntimeException("신호 저장소 장애");
        };
        ShortsFeedRanker ranker = new ShortsFeedRanker(failing, CLOCK, TIMEOUT_MS);
        List<ShortVideo> candidates = List.of(video(5L), video(3L), video(1L));

        ShortsFeedRanker.Result result = ranker.rank(candidates, null);

        assertThat(result.fallback()).isTrue();
        assertThat(result.items()).extracting(ShortVideo::getId).containsExactly(5L, 3L, 1L);
    }

    @Test
    @DisplayName("R29: 신호 계산이 제한 시간을 넘기면 결과를 기다리지 않고 fallback=true로 최신순을 돌려준다")
    void fallsBackWhenSignalsExceedTimeout() {
        ShortsRankingSignals slow = (ids, viewer) -> {
            try {
                Thread.sleep(TIMEOUT_MS * 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ShortsRankingSignals.Snapshot(Map.of(), Set.of());
        };
        ShortsFeedRanker ranker = new ShortsFeedRanker(slow, CLOCK, TIMEOUT_MS);
        List<ShortVideo> candidates = List.of(video(5L), video(3L), video(1L));

        long startedAt = System.nanoTime();
        ShortsFeedRanker.Result result = ranker.rank(candidates, null);
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(result.fallback()).isTrue();
        assertThat(result.items()).extracting(ShortVideo::getId).containsExactly(5L, 3L, 1L);
        // 실제로 제한 시간을 지켰는지 — 느린 쪽(TIMEOUT_MS*5)을 다 기다렸다면 이 상한을 넘는다.
        assertThat(elapsedMs).isLessThan(TIMEOUT_MS * 3);
    }

    @Test
    @DisplayName("R29 대조: 신호 계산이 제한 시간 안에 성공하면 폴백 없이 R28 점수대로 재정렬된다")
    void ranksNormallyWhenSignalsSucceedInTime() {
        ShortsRankingSignals fast = (ids, viewer) ->
                new ShortsRankingSignals.Snapshot(Map.of(5L, 0.0, 3L, 0.0, 1L, 1.0), Set.of());
        ShortsFeedRanker ranker = new ShortsFeedRanker(fast, CLOCK, TIMEOUT_MS);
        List<ShortVideo> candidates = List.of(video(5L), video(3L), video(1L));

        ShortsFeedRanker.Result result = ranker.rank(candidates, null);

        assertThat(result.fallback()).isFalse();
        assertThat(result.items()).extracting(ShortVideo::getId).containsExactly(1L, 5L, 3L);
    }
}
