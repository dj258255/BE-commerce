package com.beomsu.becommerce.home;

import com.beomsu.becommerce.home.internal.ImpressionRecorder;
import com.beomsu.becommerce.order.ProductCatalogFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import com.beomsu.becommerce.recommendation.RecommendationFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 홈 컴포저 — <b>후보를 모아 한 화면으로 조립한다</b>(M7).
 *
 * <h2>무엇이 판단인가</h2>
 *
 * <p>관련도만으로 화면을 채우면 <b>같은 상품이 여러 행에 반복되고 한 카테고리가 화면을 독점한다</b>.
 * 그래서 조립은 세 가지를 서로 <b>충돌시키며</b> 정한다:
 * <ul>
 *   <li><b>관련도</b> — 모델이 준 순위를 존중한다. 흩뿌리면 상위를 밀어낸다</li>
 *   <li><b>다양성</b> — 같은 대분류가 한 행을 독점하지 않게 자른다. 그 대가로 상위 항목을 포기한다</li>
 *   <li><b>정합성</b> — 품절은 넣지 않고(E4의 가용성 계약), 중복은 화면 낭비다</li>
 * </ul>
 * 셋은 동시에 만족될 수 없다 — 다양성을 올리면 관련도 순위가 내려가고, 행을 늘리면 조립 비용과 노이즈가
 * 는다. <b>그래서 {@code rules} 로 무엇을 우선할지 고르게 하고, 각 수준이 무엇을 잃는지 {@code stats}
 * 로 밝힌다.</b>
 *
 * <h2>왜 폴백이 여기서도 중요한가</h2>
 *
 * <p>추천이 실패해도(E3의 폴백) <b>홈은 비지 않는다</b> — 카탈로그만으로 행을 채우고 {@code source}로
 * 밝힌다. 홈 API가 5xx를 내면 상점 첫 화면이 통째로 실패로 보이는데, 실제로는 보여줄 상품이 있다.
 */
@Service
public class HomeComposer {

    private static final Logger log = LoggerFactory.getLogger(HomeComposer.class);

    /** 조립 규칙 — 실험의 독립변수이자, 무엇을 우선할지의 선언. */
    public enum Rules {
        /** 아무것도 하지 않는다(중복·품절 그대로). <b>기준선</b>이다 — 규칙이 실제로 무엇을 하는지 보려면 필요하다. */
        NONE,
        /** 중복 제거 + 품절 제외. "화면 낭비와 팔 수 없는 것을 막는다". */
        DEDUP,
        /** + 카테고리 다양성. "한 대분류가 화면을 독점하지 않게 한다"(기본). */
        FULL
    }

    private final RecommendationFacts recommendations;
    private final ProductCatalogFacts catalog;
    private final RecentActivityFacts recentActivity;
    private final ImpressionRecorder impressions;

    private final Rules rules;
    private final int contextLimit;
    private final int rowCap;
    private final int itemCap;
    private final int minItems;
    private final int maxPerCategory;

    public HomeComposer(RecommendationFacts recommendations,
                        ProductCatalogFacts catalog,
                        RecentActivityFacts recentActivity,
                        ImpressionRecorder impressions,
                        @Value("${app.home.rules:FULL}") Rules rules,
                        @Value("${app.home.context-limit:8}") int contextLimit,
                        @Value("${app.home.row-cap:5}") int rowCap,
                        @Value("${app.home.item-cap:8}") int itemCap,
                        @Value("${app.home.min-items:3}") int minItems,
                        @Value("${app.home.max-per-category:3}") int maxPerCategory) {
        this.recommendations = recommendations;
        this.catalog = catalog;
        this.recentActivity = recentActivity;
        this.impressions = impressions;
        this.rules = rules;
        this.contextLimit = Math.max(contextLimit, 1);
        this.rowCap = Math.max(rowCap, 1);
        this.itemCap = Math.max(itemCap, 1);
        this.minItems = Math.max(minItems, 1);
        this.maxPerCategory = Math.max(maxPerCategory, 0);
        log.info("홈 조립 규칙={} 행상한={} 항목상한={} 최소항목={} 카테고리상한={}",
                rules, this.rowCap, this.itemCap, this.minItems, this.maxPerCategory);
    }

    /**
     * 한 사용자의 홈을 조립한다.
     *
     * <p>순서가 있다: ① 컨텍스트(최근 활동) → ② 추천 → ③ 카탈로그 카드 → ④ 조립. 컨텍스트를 먼저 읽는
     * 이유는 추천이 그걸 입력으로 쓰기 때문이고, 카드를 한 번에 읽는 이유는 N+1을 피하기 위해서다.
     */
    public HomePageView compose(long userId) {
        long startedAt = System.nanoTime();

        long t0 = System.nanoTime();
        List<Long> recent = recentActivity.recentItemIds(userId, contextLimit);
        long contextMs = elapsed(t0);

        RecommendationFacts.Recommended recommended = recommendations.recommend(userId);

        // 행 후보 — 순서가 곧 우선순위다. 앞 행이 중복 제거에서 이긴다.
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("recent", "최근 본 상품", "RECENT_VIEW", recent, "최근 조회"));
        candidates.add(new Candidate("for-you", "너를 위한 추천", "MODEL_TOP_K",
                recommended.itemIds(), "모델 추천"));
        candidates.add(new Candidate("popular", "인기 상품", "POPULARITY",
                recommendations.popularItemIds(), "인기"));

        Map<Long, ProductCatalogFacts.ProductCardFacts> cards = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            for (ProductCatalogFacts.ProductCardFacts card : catalog.findAll(candidate.itemIds())) {
                cards.putIfAbsent(card.productId(), card);
            }
        }

        Assembly assembly = assemble(candidates, cards, recommended);
        long totalMs = elapsed(startedAt);

        // **추론 시간은 모델이 쓴 시간이다** — 추천 호출 전체를 넣으면 제약 확인이 두 번 세어진다
        // (실제로 그렇게 만들어 잔차가 −400ms 로 나왔다). 추천 호출의 나머지(문·컨텍스트 등)는
        // 잔차로 남는다: total − (context + model + constraint). 숨기지 않고 남기는 편이 낫다.
        // 노출 기록 — **응답과 같은 값**을 남긴다(다른 곳에서 재구성하지 않는다).
        // 기록이 실패해도 홈은 나간다(기록은 관측이고 홈은 제품이다).
        HomePageView page = new HomePageView(String.valueOf(userId), Instant.now().toString(),
                assembly.source(), recommended.fallbackReason(), null,
                new HomePageView.Latency(contextMs, recommended.modelMs(), recommended.checkMs(), totalMs),
                assembly.rows(), assembly.stats());
        impressions.record(page);
        return page;
    }

    /**
     * 조립 본체 — 후보를 행으로 만들고, 규칙을 적용하고, 무엇을 버렸는지 센다.
     *
     * <p>중복 제거는 <b>행을 가로질러</b> 한다(같은 상품이 "최근"과 "추천"에 동시에 뜨는 것이 가장 흔한
     * 낭비다). 다양성은 <b>행 안에서</b> 한다 — 페이지 전체에 적용하면 어떤 행은 카테고리가 하나도 없게
     * 되어 행의 정체성이 사라진다.
     */
    private Assembly assemble(List<Candidate> candidates,
                              Map<Long, ProductCatalogFacts.ProductCardFacts> cards,
                              RecommendationFacts.Recommended recommended) {
        Set<Long> seen = new LinkedHashSet<>();
        List<HomePageView.Row> rows = new ArrayList<>();
        int unmatched = 0;
        int outOfStock = 0;
        int duplicates = 0;
        int cappedOut = 0;
        int candidateCount = 0;
        Set<String> categories = new LinkedHashSet<>();

        for (Candidate candidate : candidates) {
            if (rules == Rules.NONE) {
                // 기준선은 규칙을 적용하지 않는다 — 카드가 없는 id 도 이름 없이 세기만 한다.
                candidateCount += candidate.itemIds().size();
            }
            List<HomePageView.Item> items = new ArrayList<>();
            Map<String, Integer> perCategory = new LinkedHashMap<>();

            for (Long itemId : candidate.itemIds()) {
                if (rules != Rules.NONE) {
                    candidateCount++;
                }
                ProductCatalogFacts.ProductCardFacts card = cards.get(itemId);
                if (card == null) {
                    unmatched++;
                    continue;
                }
                if (rules == Rules.NONE) {
                    items.add(item(card, candidate.reason()));
                    categories.add(String.valueOf(card.categoryCode()));
                    continue;
                }
                if (seen.contains(itemId)) {
                    duplicates++;
                    continue;
                }
                if (!card.inStock()) {
                    // 품절은 팔 수 없다 — E4 의 가용성 계약이 홈에서도 지켜진다.
                    outOfStock++;
                    continue;
                }
                if (rules == Rules.FULL && card.categoryCode() != null) {
                    int used = perCategory.getOrDefault(card.categoryCode(), 0);
                    if (maxPerCategory > 0 && used >= maxPerCategory) {
                        cappedOut++;   // 다양성의 대가: 같은 대분류의 다음 후보를 포기한다
                        continue;
                    }
                    perCategory.merge(card.categoryCode(), 1, Integer::sum);
                }
                seen.add(itemId);
                items.add(item(card, candidate.reason()));
                if (card.categoryCode() != null) {
                    categories.add(card.categoryCode());
                }
                if (items.size() >= itemCap) {
                    break;
                }
            }

            if (items.size() >= minItems) {
                rows.add(new HomePageView.Row(candidate.id(), candidate.title(),
                        candidate.strategy(), List.copyOf(items)));
            }
            if (rows.size() >= rowCap) {
                break;
            }
        }

        // 추천이 모델로 답했지만 카탈로그에 붙는 항목이 하나도 없으면, 홈은 카탈로그만으로 선 것이다 —
        // 그 사실을 밝힌다(숨기면 "모델이 일했다"로 읽힌다).
        boolean modelRowEmpty = rows.stream()
                .noneMatch(row -> row.strategy().equals("MODEL_TOP_K"));
        String source = (!modelRowEmpty || !"MODEL".equals(recommended.source()))
                ? recommended.source() : HomePageView.SOURCE_FALLBACK;

        return new Assembly(rows,
                new HomePageView.AssemblyStats(candidateCount, unmatched, outOfStock, duplicates,
                        cappedOut, categories.size()),
                source);
    }

    private static HomePageView.Item item(ProductCatalogFacts.ProductCardFacts card, String reason) {
        return new HomePageView.Item(String.valueOf(card.productId()), card.name(), card.price(), null, reason);
    }

    private static long elapsed(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }

    private record Candidate(String id, String title, String strategy, List<Long> itemIds, String reason) {
    }

    private record Assembly(List<HomePageView.Row> rows, HomePageView.AssemblyStats stats, String source) {
    }
}
