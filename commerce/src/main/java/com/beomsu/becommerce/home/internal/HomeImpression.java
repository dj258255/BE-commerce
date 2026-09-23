package com.beomsu.becommerce.home.internal;

import com.beomsu.becommerce.home.HomePageView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 홈 노출 기록 — <b>요청 하나가 무엇을 보여줬는가</b>(M7).
 *
 * <p><b>요청당 한 행</b>이다. 항목당 한 행이면 순위·항목별 조회가 편하지만, 홈 한 번에 20개가 나가므로
 * 30 req/s 에서 초당 600행이 되고 그 쓰기가 홈 지연에 붙는다. 요청당 한 행이면 30행이다.
 * 노출 id 를 <b>순서 그대로</b> 담아 위치를 잃지 않는다(순위가 곧 화면에서의 자리다).
 *
 * <p>판단과 대가는 ADR-043.
 */
@Entity
@Table(name = "home_impressions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HomeImpression {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private long userId;

    /** 그 응답이 어디서 왔는가({@code MODEL}|{@code FALLBACK}). */
    @Column(nullable = false, length = 20)
    private String source;

    @Column(name = "fallback_reason", length = 40)
    private String fallbackReason;

    @Column(name = "context_staleness_ms")
    private Long contextStalenessMs;

    @Column(name = "total_ms", nullable = false)
    private long totalMs;

    @Column(name = "model_ms", nullable = false)
    private long modelMs;

    @Column(name = "constraint_ms", nullable = false)
    private long constraintMs;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    /** 노출된 상품 id, 순서 그대로(쉼표 구분). */
    @Column(name = "item_ids", nullable = false, length = 4000)
    private String itemIds;

    /** 조립이 버린 것의 요약 — 나중에 "왜 이 화면인가"를 복원하는 값. */
    @Column(length = 300)
    private String stats;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private HomeImpression(long userId, String source, String fallbackReason, Long contextStalenessMs,
                           long totalMs, long modelMs, long constraintMs, int rowCount, int itemCount,
                           String itemIds, String stats, Instant createdAt) {
        this.userId = userId;
        this.source = source;
        this.fallbackReason = fallbackReason;
        this.contextStalenessMs = contextStalenessMs;
        this.totalMs = totalMs;
        this.modelMs = modelMs;
        this.constraintMs = constraintMs;
        this.rowCount = rowCount;
        this.itemCount = itemCount;
        this.itemIds = itemIds;
        this.stats = stats;
        this.createdAt = createdAt;
    }

    /** 응답에서 그대로 옮긴다 — 응답이 곧 노출의 정의다(다른 곳에서 재구성하지 않는다). */
    public static HomeImpression of(HomePageView page) {
        List<Long> ids = orderedItemIds(page);
        HomePageView.AssemblyStats stats = page.stats();
        return new HomeImpression(Long.parseLong(page.userId()),
                page.source(),
                page.fallbackReason(),
                page.contextStalenessMs(),
                page.latency().totalMs(),
                page.latency().inferenceMs(),
                page.latency().constraintMs(),
                page.rows().size(),
                ids.size(),
                ids.stream().map(String::valueOf).collect(Collectors.joining(",")),
                stats == null ? null : "candidates=%d,duplicates=%d,outOfStock=%d,cappedOut=%d,unmatched=%d"
                        .formatted(stats.candidates(), stats.duplicates(), stats.outOfStock(),
                                stats.cappedOut(), stats.unmatched()),
                Instant.now());
    }

    /** 화면 순서대로 편 목록 — 행 순서가 노출 순서다. */
    private static List<Long> orderedItemIds(HomePageView page) {
        return page.rows().stream()
                .flatMap(row -> row.items().stream())
                .map(item -> Long.parseLong(item.itemId()))
                .toList();
    }
}
