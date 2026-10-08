package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R28: 숏폼 피드 개인화 점수(완료율 0.5·최신성 0.3·상품 선호 일치 0.2) 정렬을 검증한다.
 *
 * <p>시계를 {@code Instant}로 직접 주입해 "최신성"을 결정적으로 고정한다 — 실제 시간이 흘러도
 * 테스트 결과가 바뀌지 않는다.
 */
class ShortsFeedRankingTest {

    private static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    private static final UploadMeta META = new UploadMeta(20, 1_000_000L, 1080, 1920, "video/mp4");

    private static ShortVideo videoAt(long id, Instant createdAt) {
        ShortVideo v = ShortVideo.upload(1L, "shorts/1/" + id, META);
        ReflectionTestUtils.setField(v, "id", id);
        ReflectionTestUtils.setField(v, "createdAt", createdAt);
        return v;
    }

    private static ShortsRankingSignals.Snapshot snapshot(Map<Long, Double> completion, Set<Long> preferred) {
        return new ShortsRankingSignals.Snapshot(completion, preferred);
    }

    @Test
    @DisplayName("R28: 같은 시각·같은 상품 선호면 완료율이 높은 영상이 앞선다")
    void higherCompletionRateRanksFirst() {
        ShortVideo low = videoAt(1L, NOW);
        ShortVideo high = videoAt(2L, NOW);
        ShortsRankingSignals.Snapshot snapshot = snapshot(Map.of(1L, 0.1, 2L, 0.9), Set.of());

        List<ShortVideo> ranked = ShortsFeedRanking.rank(List.of(low, high), snapshot, NOW);

        assertThat(ranked).extracting(ShortVideo::getId).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("R28: 완료율·상품 선호가 같으면 더 최근에 올라온 영상이 앞선다")
    void higherRecencyRanksFirstWhenOtherSignalsTie() {
        ShortVideo old = videoAt(1L, NOW.minus(48, ChronoUnit.HOURS));
        ShortVideo fresh = videoAt(2L, NOW);
        ShortsRankingSignals.Snapshot snapshot = snapshot(Map.of(1L, 0.5, 2L, 0.5), Set.of());

        List<ShortVideo> ranked = ShortsFeedRanking.rank(List.of(old, fresh), snapshot, NOW);

        assertThat(ranked).extracting(ShortVideo::getId).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("R28: 상품 선호와 겹치는 상품이 연결된 영상이 그렇지 않은 영상보다 앞선다(다른 신호는 동일)")
    void productPreferenceMatchBoostsRank() {
        ShortVideo matching = videoAt(1L, NOW);
        matching.linkProduct(100L);
        ShortVideo notMatching = videoAt(2L, NOW);
        notMatching.linkProduct(200L);
        ShortsRankingSignals.Snapshot snapshot = snapshot(Map.of(1L, 0.5, 2L, 0.5), Set.of(100L));

        List<ShortVideo> ranked = ShortsFeedRanking.rank(List.of(notMatching, matching), snapshot, NOW);

        assertThat(ranked).extracting(ShortVideo::getId).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("R28: 신호가 전혀 없는(완료율 기록 없음·선호 없음) 영상은 0으로 취급되고 최신성만으로 순위가 매겨진다")
    void missingSignalsDefaultToZero() {
        ShortVideo noData = videoAt(1L, NOW.minus(1, ChronoUnit.HOURS));
        ShortVideo withData = videoAt(2L, NOW.minus(2, ChronoUnit.HOURS));
        // 1번은 완료율 신호가 없다(맵에 없음) → 0으로 취급. 2번은 완료율 1.0.
        ShortsRankingSignals.Snapshot snapshot = snapshot(Map.of(2L, 1.0), Set.of());

        List<ShortVideo> ranked = ShortsFeedRanking.rank(List.of(noData, withData), snapshot, NOW);

        // 완료율 가중치(0.5)가 최신성 1시간 차보다 크므로 데이터가 있는 2번이 앞선다.
        assertThat(ranked).extracting(ShortVideo::getId).containsExactly(2L, 1L);
    }

    @Test
    @DisplayName("R28: 점수가 완전히 같으면 최신순(id 내림차순)이고, 같은 입력을 다시 넣어도 같은 순서가 나온다(결정적)")
    void tiesBreakByRecencyAndOrderingIsDeterministic() {
        ShortVideo a = videoAt(10L, NOW);
        ShortVideo b = videoAt(20L, NOW);
        ShortVideo c = videoAt(5L, NOW);
        ShortsRankingSignals.Snapshot snapshot = snapshot(Map.of(), Set.of());

        List<ShortVideo> first = ShortsFeedRanking.rank(List.of(a, b, c), snapshot, NOW);
        List<ShortVideo> second = ShortsFeedRanking.rank(List.of(a, b, c), snapshot, NOW);

        assertThat(first).extracting(ShortVideo::getId).containsExactly(20L, 10L, 5L);
        assertThat(second).extracting(ShortVideo::getId).containsExactly(20L, 10L, 5L);
    }
}
