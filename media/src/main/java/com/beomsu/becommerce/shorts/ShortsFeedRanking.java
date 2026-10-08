package com.beomsu.becommerce.shorts;

import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 숏폼 피드 개인화 점수(R28) — DB·시간을 모르는 순수 로직. {@code now}를 받아서만 "최신성"을
 * 계산하므로 시계를 주입한 결정적 테스트가 가능하다.
 *
 * <p>가중치·신호 정의·초기값의 근거는 {@code docs/shorts-feed-ranking.md}에 문서로 남긴다
 * (가중치를 바꾸려면 상수와 그 문서를 함께 고쳐 변경 이력이 남게 한다).
 *
 * <p>점수를 매기는 범위는 <b>이 호출에 넘어온 후보 목록뿐</b>이다 — {@code ShortsFeedService}가
 * 커서로 가져온 한 쪽(최대 {@code pageSize+1}개)을 그대로 넘기므로, 전체 카탈로그를 재정렬하는
 * 것이 아니라 <b>이미 뽑힌 최신 후보군 안에서 순서만 바꾼다</b>(커서 페이지네이션 계약은 그대로
 * 유지하기 위한 범위 제한 — docs 참고).
 */
final class ShortsFeedRanking {

    /** 완료율 0.5 · 최신성 0.3 · 상품 선호 일치 0.2 (R28, 초기값). */
    static final double WEIGHT_COMPLETION = 0.5;
    static final double WEIGHT_RECENCY = 0.3;
    static final double WEIGHT_PRODUCT_MATCH = 0.2;

    /** 최신성 반감기 — 영상이 24시간 지날 때마다 최신성 점수가 절반이 된다(가정, 실측 전). */
    static final long RECENCY_HALF_LIFE_SECONDS = Duration.ofHours(24).toSeconds();

    private ShortsFeedRanking() {
    }

    /**
     * 가중합 점수 내림차순으로 정렬한다. 같은 점수면 최신순(id 내림차순 — 이 저장소의 "id가 곧
     * 생성 순서" 관례, R26과 같다)이고, 그 비교자까지 전부 입력의 함수이므로 같은 입력이면 항상
     * 같은 순서가 나온다(결정적).
     */
    static List<ShortVideo> rank(List<ShortVideo> candidates, ShortsRankingSignals.Snapshot snapshot, Instant now) {
        Comparator<ShortVideo> byScoreDesc = Comparator
                .comparingDouble((ShortVideo v) -> score(v, snapshot, now)).reversed()
                .thenComparing(ShortVideo::getId, Comparator.reverseOrder());
        return candidates.stream().sorted(byScoreDesc).toList();
    }

    static double score(ShortVideo video, ShortsRankingSignals.Snapshot snapshot, Instant now) {
        double completion = snapshot.completionRateByVideo().getOrDefault(video.getId(), 0.0);
        double recency = recencyScore(video.getCreatedAt(), now);
        double productMatch = productMatchScore(video, snapshot.preferredProductIds());
        return WEIGHT_COMPLETION * completion + WEIGHT_RECENCY * recency + WEIGHT_PRODUCT_MATCH * productMatch;
    }

    private static double recencyScore(Instant createdAt, Instant now) {
        long ageSeconds = Math.max(0, Duration.between(createdAt, now).getSeconds());
        return RECENCY_HALF_LIFE_SECONDS / (double) (RECENCY_HALF_LIFE_SECONDS + ageSeconds);
    }

    private static double productMatchScore(ShortVideo video, Set<Long> preferredProductIds) {
        List<Long> linked = video.getLinkedProductIds();
        if (linked.isEmpty() || preferredProductIds.isEmpty()) {
            return 0.0;
        }
        long overlap = linked.stream().filter(preferredProductIds::contains).count();
        return overlap / (double) linked.size();
    }
}
