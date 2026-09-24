package com.beomsu.becommerce.home;

import com.beomsu.becommerce.home.internal.ImpressionRecorder;
import com.beomsu.becommerce.order.ProductCatalogFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import com.beomsu.becommerce.recommendation.RecommendationFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final int candidateDepth;
    private final int pageRows;

    /** 다음 쪽 카테고리 행이 후보로 쓰는 인기 표 깊이 — 인기 표 전체(200행)다. */
    static final int POPULAR_DEPTH = 200;
    static final String SOURCE_CATALOG = "CATALOG";

    public HomeComposer(RecommendationFacts recommendations,
                        ProductCatalogFacts catalog,
                        RecentActivityFacts recentActivity,
                        ImpressionRecorder impressions,
                        Rules rules, int contextLimit, int rowCap, int itemCap, int minItems,
                        int maxPerCategory, int refillDepth) {
        this(recommendations, catalog, recentActivity, impressions, rules, contextLimit, rowCap, itemCap,
                minItems, maxPerCategory, refillDepth, 3);
    }

    @Autowired
    public HomeComposer(RecommendationFacts recommendations,
                        ProductCatalogFacts catalog,
                        RecentActivityFacts recentActivity,
                        ImpressionRecorder impressions,
                        @Value("${app.home.rules:FULL}") Rules rules,
                        @Value("${app.home.context-limit:8}") int contextLimit,
                        @Value("${app.home.row-cap:5}") int rowCap,
                        @Value("${app.home.item-cap:8}") int itemCap,
                        @Value("${app.home.min-items:3}") int minItems,
                        @Value("${app.home.max-per-category:3}") int maxPerCategory,
                        @Value("${app.home.refill-depth:1}") int refillDepth,
                        @Value("${app.home.page-rows:3}") int pageRows) {
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
        // 되채우기 깊이(#198①) — 1이면 행마다 항목 상한만큼만 후보를 받는다(규칙 도입 전과 같은 동작).
        // 2면 2배까지: 규칙이 버린 칸을 **더 깊은 후보**로 채운다. 그 대가는 관련도(평균 표시 순위)다.
        this.candidateDepth = Math.max(refillDepth, 1) * this.itemCap;
        this.pageRows = Math.max(pageRows, 1);
        log.info("홈 조립 규칙={} 행상한={} 항목상한={} 최소항목={} 카테고리상한={} 후보깊이={}",
                rules, this.rowCap, this.itemCap, this.minItems, this.maxPerCategory, this.candidateDepth);
    }

    /**
     * 한 사용자의 홈을 조립한다.
     *
     * <p>순서가 있다: ① 컨텍스트(최근 활동) → ② 추천 → ③ 카탈로그 카드 → ④ 조립. 컨텍스트를 먼저 읽는
     * 이유는 추천이 그걸 입력으로 쓰기 때문이고, 카드를 한 번에 읽는 이유는 N+1을 피하기 위해서다.
     */
    public HomePageView compose(long userId) {
        return compose(userId, HomeCursor.first());
    }

    /**
     * 한 쪽을 조립한다(#237). 1쪽은 {@link #compose(long)} 과 같은 세 행이고, 2쪽부터는
     * {@link #composeNext 대분류별 인기 행}이다.
     */
    public HomePageView compose(long userId, HomeCursor cursor) {
        if (cursor.page() > 1) {
            return composeNext(userId, cursor);
        }
        long startedAt = System.nanoTime();

        long t0 = System.nanoTime();
        // 최근 활동은 **이 행만** 쓴다(컨텍스트는 추천 모듈이 스스로 읽는다) — 그래서 깊이를 늘려도
        // 모델이 보는 컨텍스트는 그대로다. 되채우기의 효과가 다른 축과 섞이지 않는다.
        List<Long> recent = recentActivity.recentItemIds(userId, candidateDepth);
        long contextMs = elapsed(t0);

        RecommendationFacts.Recommended recommended = recommendations.recommend(userId);

        // 행 후보 — 순서가 곧 우선순위다. 앞 행이 중복 제거에서 이긴다.
        // **모델 행의 깊이는 그대로 둔다**: 모델에 더 많이 요구하려면 result-size 를 키워야 하고,
        // 그 값은 과부하 게이트의 지연 추정에 들어가 **입장 판단까지 바뀐다**(E5 영역).
        // 되채우기는 원천이 싼 곳(최근 활동·인기 표)에만 적용한다 — 그 사실을 측정 리포트에 적었다.
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("recent", "최근 본 상품", "RECENT_VIEW", recent, "최근 조회"));
        candidates.add(new Candidate("for-you", "너를 위한 추천", "MODEL_TOP_K",
                recommended.itemIds(), "모델 추천"));
        candidates.add(new Candidate("popular", "인기 상품", "POPULARITY",
                recommendations.popularItemIds(candidateDepth), "인기"));

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
                assembly.rows(), assembly.stats(), 1, firstNextCursor(assembly.rows()),
                recommended.experiment(), recommended.variant());
        impressions.record(page);
        return page;
    }

    /** 1쪽이 보여 준 상품과 행을 담은 2쪽 커서. 1쪽 뒤에는 카테고리 행이 늘 남아 있으므로 비우지 않는다. */
    private static String firstNextCursor(List<HomePageView.Row> rows) {
        List<Long> shown = rows.stream().flatMap(r -> r.items().stream())
                .map(i -> Long.parseLong(i.itemId())).toList();
        return HomeCursor.first().next(shown, rows.stream().map(HomePageView.Row::id).toList()).encode();
    }

    /**
     * 2쪽부터 — <b>대분류별 인기 행</b>(#237).
     *
     * <p>GenPage 가 다음 쪽을 만들 때 하는 일 둘을 그대로 한다.
     * <ul>
     *   <li><b>앞에서 보여 준 것을 입력에 넣는다</b> — 커서의 상품은 다시 넣지 않는다. 쪽을 넘겨도 같은 상품이
     *       돌아오지 않는다</li>
     *   <li><b>그 사이의 세션 활동을 반영한다</b> — 최근 활동을 <b>이 요청 시점에</b> 다시 읽어, 방금 본 상품의
     *       대분류 행을 먼저 놓는다. 1쪽 이후 원피스를 봤다면 2쪽 첫 행이 여성복이다. 그 행은
     *       {@code CATEGORY_POPULAR_SESSION} 으로 표시한다 — 세션이 순서를 바꿨는지 응답만 보고 알 수 있다</li>
     * </ul>
     *
     * <p>모델이 행을 만들 때 무엇을 입력으로 넣을지는 추천 모듈이 정한다(#270, ADR-066). 구매 이력을 쓰는 사용자면
     * 구매만 넣으므로 위의 세션 반영은 규칙 행에만 남는다.
     *
     * <p>한 대분류는 한 번만 행이 된다. 시도한 대분류는 행이 못 되더라도(항목이 {@code minItems} 미만) 커서에
     * 남겨 다음 쪽에서 다시 시도하지 않는다. 시도할 대분류가 남지 않으면 {@code nextCursor} 가 {@code null} 이다.
     */
    private HomePageView composeNext(long userId, HomeCursor cursor) {
        long startedAt = System.nanoTime();
        long t0 = System.nanoTime();
        List<Long> recent = recentActivity.recentItemIds(userId, contextLimit);
        long contextMs = elapsed(t0);
        List<Long> popular = recommendations.popularItemIds(POPULAR_DEPTH);

        Set<Long> wanted = new LinkedHashSet<>(recent);
        wanted.addAll(popular);
        Map<Long, ProductCatalogFacts.ProductCardFacts> cards = new LinkedHashMap<>();
        for (ProductCatalogFacts.ProductCardFacts card : catalog.findAll(List.copyOf(wanted))) {
            cards.put(card.productId(), card);
        }

        // 세션이 고른 대분류(방금 본 것 순서) → 인기 표에 처음 나오는 순서로 나머지
        Set<String> sessionCategories = new LinkedHashSet<>();
        for (Long id : recent) {
            ProductCatalogFacts.ProductCardFacts card = cards.get(id);
            if (card != null && card.categoryCode() != null) {
                sessionCategories.add(card.categoryCode());
            }
        }
        Set<String> order = new LinkedHashSet<>(sessionCategories);
        for (Long id : popular) {
            ProductCatalogFacts.ProductCardFacts card = cards.get(id);
            if (card != null && card.categoryCode() != null) {
                order.add(card.categoryCode());
            }
        }
        order.removeIf(category -> cursor.usedRows().contains(rowId(category)));

        Map<String, String> names = catalog.topCategoryNames();

        // 모델이 켜져 있으면 행을 모델이 생성한다(GenPage, #238). 빈 목록이면 아래 규칙 행으로 물러선다.
        Set<String> usedCategories = new LinkedHashSet<>();
        cursor.usedRows().stream().filter(r -> r.startsWith("cat:")).forEach(r -> usedCategories.add(r.substring(4)));
        List<RecommendationFacts.GeneratedRow> generated =
                recommendations.generatePageRows(userId, recent, cursor.shown(), usedCategories, pageRows, itemCap);
        if (!generated.isEmpty()) {
            return composeGenerated(userId, cursor, generated, names, usedCategories, contextMs, startedAt);
        }

        List<HomePageView.Row> rows = new ArrayList<>();
        List<Long> newlyShown = new ArrayList<>();
        List<String> tried = new ArrayList<>();
        int duplicates = 0;
        int outOfStock = 0;
        Set<String> distinct = new LinkedHashSet<>();
        for (String category : order) {
            if (rows.size() >= pageRows) {
                break;
            }
            tried.add(rowId(category));
            List<HomePageView.Item> items = new ArrayList<>();
            int position = 0;
            for (Long id : popular) {
                position++;
                ProductCatalogFacts.ProductCardFacts card = cards.get(id);
                if (card == null || !category.equals(card.categoryCode())) {
                    continue;
                }
                if (cursor.shown().contains(id) || newlyShown.contains(id)) {
                    duplicates++;   // 앞 쪽에서 이미 보여 준 상품 — 커서가 없었다면 다시 나갔다
                    continue;
                }
                if (!card.inStock()) {
                    outOfStock++;
                    continue;
                }
                items.add(item(card, "대분류 인기", position));
                if (items.size() >= itemCap) {
                    break;
                }
            }
            if (items.size() < itemCap) {
                // 인기 표에 이 대분류가 모자라면 그 대분류의 신상품으로 채운다. 채우지 않으면 행이 안 서고,
                // 사용자가 방금 본 대분류가 세션 신호였어도 조용히 사라진다(실측에서 아동복 6건이 그랬다).
                fillFromCategory(category, items, cursor, newlyShown);
            }
            if (items.size() >= minItems) {
                boolean fromSession = sessionCategories.contains(category);
                rows.add(new HomePageView.Row(rowId(category), names.getOrDefault(category, category) + " 인기",
                        fromSession ? "CATEGORY_POPULAR_SESSION" : "CATEGORY_POPULAR", List.copyOf(items)));
                items.forEach(i -> newlyShown.add(Long.parseLong(i.itemId())));
                distinct.add(category);
            }
        }

        boolean more = order.size() > tried.size();
        String next = more ? cursor.next(newlyShown, tried).encode() : null;
        HomePageView page = new HomePageView(String.valueOf(userId), Instant.now().toString(),
                SOURCE_CATALOG, null, null,
                new HomePageView.Latency(contextMs, 0, 0, elapsed(startedAt)),
                rows, new HomePageView.AssemblyStats(popular.size(), 0, outOfStock, duplicates, 0, distinct.size()),
                cursor.page(), next);
        impressions.record(page);
        return page;
    }

    /**
     * 모델이 생성한 행으로 쪽을 만든다(#238). 모델은 중복·앞 쪽 상품·대분류 일치를 <b>생성 중에</b> 지켰고,
     * 여기서는 모델이 모르는 <b>품절만 생성 뒤에</b> 거른다(ADR-050). 행은 {@code GENPAGE} 로 표시한다.
     */
    private HomePageView composeGenerated(long userId, HomeCursor cursor,
                                          List<RecommendationFacts.GeneratedRow> generated,
                                          Map<String, String> names, Set<String> usedCategories,
                                          long contextMs, long startedAt) {
        List<Long> all = generated.stream().flatMap(r -> r.itemIds().stream()).toList();
        Map<Long, ProductCatalogFacts.ProductCardFacts> cards = new LinkedHashMap<>();
        catalog.findAll(all).forEach(card -> cards.put(card.productId(), card));
        List<HomePageView.Row> rows = new ArrayList<>();
        List<Long> newlyShown = new ArrayList<>();
        List<String> tried = new ArrayList<>();
        int outOfStock = 0;
        int duplicates = 0;
        int unmatched = 0;
        for (RecommendationFacts.GeneratedRow row : generated) {
            tried.add(rowId(row.category()));
            List<HomePageView.Item> items = new ArrayList<>();
            int position = 0;
            for (Long id : row.itemIds()) {
                position++;
                ProductCatalogFacts.ProductCardFacts card = cards.get(id);
                if (card == null) {
                    unmatched++;   // 모델 어휘에는 있는데 카탈로그에 없는 상품
                    continue;
                }
                if (cursor.shown().contains(id) || newlyShown.contains(id)) {
                    duplicates++;   // 모델 마스크가 맞으면 0 이다
                    continue;
                }
                if (!card.inStock()) {
                    outOfStock++;
                    continue;
                }
                items.add(item(card, "모델 생성", position));
                newlyShown.add(id);
                if (items.size() >= itemCap) {
                    break;
                }
            }
            if (items.size() >= minItems) {
                rows.add(new HomePageView.Row(rowId(row.category()),
                        names.getOrDefault(row.category(), row.category()) + " 추천", "GENPAGE", List.copyOf(items)));
            }
        }
        Set<String> remaining = new LinkedHashSet<>(names.keySet());
        remaining.removeAll(usedCategories);
        generated.forEach(r -> remaining.remove(r.category()));
        String next = remaining.isEmpty() ? null : cursor.next(newlyShown, tried).encode();
        HomePageView page = new HomePageView(String.valueOf(userId), Instant.now().toString(),
                "GENPAGE", null, null,
                new HomePageView.Latency(contextMs, 0, 0, elapsed(startedAt)),
                rows, new HomePageView.AssemblyStats(all.size(), unmatched, outOfStock, duplicates, 0, rows.size()),
                cursor.page(), next);
        impressions.record(page);
        return page;
    }

    /**
     * 대분류 신상품으로 행의 빈칸을 채운다. 앞 쪽에서 보여 준 것·이 쪽에서 이미 쓴 것·품절은 뺀다.
     * 채운 수를 돌려준다 — 응답의 {@code reason} 이 "대분류 신상품"이라 인기와 구분된다.
     */
    private int fillFromCategory(String category, List<HomePageView.Item> items, HomeCursor cursor,
                                 List<Long> newlyShown) {
        Set<String> already = new LinkedHashSet<>();
        items.forEach(i -> already.add(i.itemId()));
        int before = items.size();
        List<Long> newest = catalog.newestInCategory(category, itemCap * 3);
        Map<Long, ProductCatalogFacts.ProductCardFacts> byId = new LinkedHashMap<>();
        catalog.findAll(newest).forEach(card -> byId.put(card.productId(), card));
        int position = 0;
        for (Long id : newest) {   // findAll 은 순서를 지키지 않는다 — 신상품 순서는 id 목록이 쥔다
            position++;
            ProductCatalogFacts.ProductCardFacts card = byId.get(id);
            if (card == null) {
                continue;
            }
            if (items.size() >= itemCap) {
                break;
            }
            if (cursor.shown().contains(id) || newlyShown.contains(id) || already.contains(String.valueOf(id))
                    || !card.inStock()) {
                continue;
            }
            items.add(item(card, "대분류 신상품", position));
            already.add(String.valueOf(id));
        }
        return items.size() - before;
    }

    private static String rowId(String category) {
        return "cat:" + category;
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
            int position = 0;   // 후보 리스트에서의 자리(1부터). **되채우기 깊이의 대리 지표**다.

            for (Long itemId : candidate.itemIds()) {
                position++;
                if (rules != Rules.NONE) {
                    candidateCount++;
                }
                ProductCatalogFacts.ProductCardFacts card = cards.get(itemId);
                if (card == null) {
                    unmatched++;
                    continue;
                }
                if (rules == Rules.NONE) {
                    items.add(item(card, candidate.reason(), position));
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
                items.add(item(card, candidate.reason(), position));
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

    private static HomePageView.Item item(ProductCatalogFacts.ProductCardFacts card, String reason, int rank) {
        return new HomePageView.Item(String.valueOf(card.productId()), card.name(), card.price(),
                card.imageUrl(), card.inStock(), null, reason, card.categoryCode(), rank);
    }

    private static long elapsed(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }

    private record Candidate(String id, String title, String strategy, List<Long> itemIds, String reason) {
    }

    private record Assembly(List<HomePageView.Row> rows, HomePageView.AssemblyStats stats, String source) {
    }
}
