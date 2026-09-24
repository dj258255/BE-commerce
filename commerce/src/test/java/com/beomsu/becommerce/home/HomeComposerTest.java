package com.beomsu.becommerce.home;

import com.beomsu.becommerce.home.internal.ImpressionRecorder;
import com.beomsu.becommerce.order.ProductCatalogFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import com.beomsu.becommerce.recommendation.RecommendationFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 홈 조립의 <b>판단</b>을 고정한다 — M7.
 *
 * <p>여기서 지키려는 것은 성능이 아니라 <b>무엇을 버리는가</b>다. 조립은 관련도·다양성·정합성을
 * 동시에 만족시킬 수 없으므로 셋 중 무엇을 우선하는지가 코드에 있어야 하고, 그 선택이
 * 응답의 {@code stats} 로 드러나야 한다(조용히 지우면 "왜 이 화면인가"를 아무도 복원할 수 없다).
 */
class HomeComposerTest {

    private static final long USER = 7L;

    private RecommendationFacts recommendations;
    private ProductCatalogFacts catalog;
    private RecentActivityFacts recentActivity;
    private ImpressionRecorder impressions;

    @BeforeEach
    void setUp() {
        recommendations = mock(RecommendationFacts.class);
        catalog = mock(ProductCatalogFacts.class);
        recentActivity = mock(RecentActivityFacts.class);
        impressions = mock(ImpressionRecorder.class);
        when(recentActivity.recentItemIds(USER, 8)).thenReturn(List.of());
        when(recommendations.popularItemIds(anyInt())).thenReturn(List.of());
    }

    private HomeComposer composer(HomeComposer.Rules rules, int maxPerCategory, int minItems) {
        return composer(rules, maxPerCategory, minItems, 1);
    }

    private HomeComposer composer(HomeComposer.Rules rules, int maxPerCategory, int minItems, int refillDepth) {
        return new HomeComposer(recommendations, catalog, recentActivity, impressions, rules, 8, 5, 8,
                minItems, maxPerCategory, refillDepth);
    }

    private static ProductCatalogFacts.ProductCardFacts card(long id, String category, boolean inStock) {
        return new ProductCatalogFacts.ProductCardFacts(id, "상품" + id, 10_000L, "브랜드", "/x.jpg", category, inStock);
    }

    private void modelReturns(List<Long> ids) {
        when(recommendations.recommend(USER))
                .thenReturn(new RecommendationFacts.Recommended(ids, "MODEL", null, 50, 4, "RANKING"));
    }

    @Test
    @DisplayName("되채우기는 더 깊은 후보를 산다 — 화면은 길어지고 평균 표시 순위는 내려간다")
    void refillTradesRelevanceForLength() {
        // 인기 후보: 앞 12개가 전부 한 대분류(a)라 상한 1이면 **첫 8개에서 1개만** 남는다.
        // 그래서 되채우기 깊이가 화면 길이를 가른다.
        List<Long> popularIds = new java.util.ArrayList<>();
        for (long id = 1; id <= 12; id++) {
            popularIds.add(id);
        }
        popularIds.addAll(List.of(13L, 14L, 15L));
        when(recommendations.popularItemIds(anyInt())).thenAnswer(invocation ->
                popularIds.subList(0, Math.min(invocation.getArgument(0), popularIds.size())));
        when(catalog.findAll(anyList())).thenAnswer(invocation -> {
            List<Long> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> card(id, id <= 12 ? "a" : "x" + id, true))
                    .toList();
        });
        modelReturns(List.of());

        HomePageView withoutRefill = composer(HomeComposer.Rules.FULL, 1, 1, 1).compose(USER);
        HomePageView withRefill = composer(HomeComposer.Rules.FULL, 1, 1, 2).compose(USER);

        List<HomePageView.Item> before = itemsOf(withoutRefill, "POPULARITY");
        List<HomePageView.Item> after = itemsOf(withRefill, "POPULARITY");

        // 되채우기 없이는 첫 묶음에서 1개뿐 — 나머지는 다양성 상한이 버렸다.
        assertThat(before).hasSize(1);
        // 켜면 더 깊은 후보(13~15)로 칸을 채운다: 화면이 길어진다.
        assertThat(after).hasSize(4);
        // **그 대가를 같은 객체가 밝힌다** — 추가된 항목의 순위는 13 이상이다(앞 묶음이 아니다).
        assertThat(after).filteredOn(item -> item.rank() > 12).hasSize(3);
        assertThat(avgRank(before)).isLessThan(avgRank(after));
    }

    private static List<HomePageView.Item> itemsOf(HomePageView page, String strategy) {
        return page.rows().stream()
                .filter(row -> row.strategy().equals(strategy))
                .flatMap(row -> row.items().stream())
                .toList();
    }

    private static double avgRank(List<HomePageView.Item> items) {
        return items.stream().mapToInt(HomePageView.Item::rank).average().orElse(0);
    }

    @Test
    @DisplayName("조립한 화면을 노출 기록에 넘긴다 — 응답과 같은 값을 남겨야 복원할 수 있다")
    void recordsTheImpression() {
        modelReturns(List.of(1L, 2L, 3L));
        when(catalog.findAll(List.of(1L, 2L, 3L))).thenReturn(List.of(card(1, "fashion", true), card(2, "digital", true), card(3, "living", true)));

        HomePageView page = composer(HomeComposer.Rules.FULL, 3, 1).compose(USER);

        // 다른 곳에서 재구성하지 않고 **응답 자체**를 넘긴다 — 둘이 갈라지면 기록이 거짓이 된다.
        verify(impressions).record(page);
    }

    @Test
    @DisplayName("중복은 행을 가로질러 걸러진다 — 같은 상품이 두 행에 뜨는 것이 가장 흔한 낭비다")
    void deduplicatesAcrossRows() {
        when(recentActivity.recentItemIds(USER, 8)).thenReturn(List.of(1L, 2L, 3L));
        modelReturns(List.of(3L, 4L, 5L, 6L));
        when(catalog.findAll(List.of(1L, 2L, 3L))).thenReturn(List.of(card(1, "fashion", true), card(2, "fashion", true), card(3, "fashion", true)));
        when(catalog.findAll(List.of(3L, 4L, 5L, 6L))).thenReturn(List.of(card(3, "fashion", true), card(4, "digital", true), card(5, "digital", true), card(6, "digital", true)));

        HomePageView page = composer(HomeComposer.Rules.DEDUP, 0, 2).compose(USER);

        // 3 은 "최근" 행이 가져간다 — 먼저 온 행이 이긴다(순서가 곧 우선순위다).
        assertThat(page.rows()).extracting(HomePageView.Row::id).containsExactly("recent", "for-you");
        assertThat(page.rows().get(0).items()).extracting(HomePageView.Item::itemId).containsExactly("1", "2", "3");
        assertThat(page.rows().get(1).items()).extracting(HomePageView.Item::itemId).containsExactly("4", "5", "6");
        assertThat(page.stats().duplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("품절은 넣지 않는다 — 팔 수 없는 것을 첫 화면에 걸면 구매 단계에서 깨진다")
    void dropsOutOfStock() {
        modelReturns(List.of(1L, 2L, 3L));
        when(catalog.findAll(List.of(1L, 2L, 3L))).thenReturn(List.of(card(1, "fashion", true), card(2, "fashion", false), card(3, "fashion", true)));

        HomePageView page = composer(HomeComposer.Rules.DEDUP, 0, 1).compose(USER);

        assertThat(page.rows().get(0).items()).extracting(HomePageView.Item::itemId).containsExactly("1", "3");
        assertThat(page.stats().outOfStock()).isEqualTo(1);
    }

    @Test
    @DisplayName("다양성 규칙은 같은 대분류를 자른다 — 그 대가로 상위 항목을 포기한다")
    void diversityCapsSameCategory() {
        modelReturns(List.of(1L, 2L, 3L, 4L));
        when(catalog.findAll(List.of(1L, 2L, 3L, 4L)))
                .thenReturn(List.of(card(1, "fashion", true), card(2, "fashion", true), card(3, "digital", true), card(4, "fashion", true)));

        HomePageView capped = composer(HomeComposer.Rules.FULL, 1, 1).compose(USER);
        HomePageView uncapped = composer(HomeComposer.Rules.DEDUP, 0, 1).compose(USER);

        // 상한 1: fashion 은 하나만, 그 다음 다른 대분류가 들어온다.
        assertThat(capped.rows().get(0).items()).extracting(HomePageView.Item::itemId).containsExactly("1", "3");
        // 상한이 없으면 관련도 순서 그대로다 — **다양성이 관련도를 이긴 것이 아니라 대체한 것이다.**
        assertThat(uncapped.rows().get(0).items()).extracting(HomePageView.Item::itemId).containsExactly("1", "2", "3", "4");
    }

    @Test
    @DisplayName("기준선(NONE)은 아무것도 버리지 않는다 — 규칙이 실제로 무엇을 하는지 재려면 필요하다")
    void baselineDropsNothing() {
        modelReturns(List.of(1L, 2L));
        when(catalog.findAll(List.of(1L, 2L))).thenReturn(List.of(card(1, "fashion", true), card(2, "fashion", false)));

        HomePageView page = composer(HomeComposer.Rules.NONE, 0, 1).compose(USER);

        // 품절도 그대로 나간다 — 이 수준이 "규칙 없음"의 정의다.
        assertThat(page.rows().get(0).items()).extracting(HomePageView.Item::itemId).containsExactly("1", "2");
        assertThat(page.stats().outOfStock()).isZero();
    }

    @Test
    @DisplayName("최소 항목 수에 못 미치는 행은 버린다 — 한 칸짜리 행은 빈 행으로 보인다")
    void dropsRowsBelowMinimum() {
        modelReturns(List.of(1L, 2L, 3L));
        when(catalog.findAll(List.of(1L, 2L, 3L))).thenReturn(List.of(card(1, "fashion", true), card(2, "fashion", true)));

        HomePageView page = composer(HomeComposer.Rules.DEDUP, 0, 3).compose(USER);

        assertThat(page.rows()).isEmpty();
        // 버렸다는 사실은 남는다 — 후보 3개가 와서 2개만 카드가 됐고 그 행은 최소 미달이었다.
        assertThat(page.stats().candidates()).isEqualTo(3);
    }

    @Test
    @DisplayName("모델이 준 id 가 카탈로그에 없으면 홈은 카탈로그만으로 서고, 그 사실을 밝힌다")
    void degradesWhenModelItemsAreNotInCatalog() {
        modelReturns(List.of(999_001L, 999_002L, 999_003L));
        when(recommendations.popularItemIds(anyInt())).thenReturn(List.of(1L, 2L, 3L));
        when(catalog.findAll(List.of(999_001L, 999_002L, 999_003L))).thenReturn(List.of());
        when(catalog.findAll(List.of(1L, 2L, 3L))).thenReturn(List.of(card(1, "fashion", true), card(2, "digital", true), card(3, "living", true)));

        HomePageView page = composer(HomeComposer.Rules.FULL, 3, 1).compose(USER);

        // 모델은 MODEL 로 답했지만 화면에 모델 행이 없다 — 그때 FALLBACK 이라고 말해야 정직하다.
        assertThat(page.source()).isEqualTo(HomePageView.SOURCE_FALLBACK);
        assertThat(page.stats().unmatched()).isEqualTo(3);
        assertThat(page.rows()).extracting(HomePageView.Row::id).containsExactly("popular");
    }

    // ---- 다음 쪽(#237) ----

    private void popularByCategory() {
        // 인기 표: a 대분류 1~6, b 7~12, c 13~18 (id 순서가 곧 인기 순위)
        List<Long> ids = new java.util.ArrayList<>();
        for (long id = 1; id <= 18; id++) {
            ids.add(id);
        }
        when(recommendations.popularItemIds(anyInt())).thenAnswer(inv -> ids.subList(0, Math.min(inv.getArgument(0), ids.size())));
        when(catalog.findAll(anyList())).thenAnswer(inv -> {
            List<Long> want = inv.getArgument(0);
            return want.stream().map(id -> card(id, id <= 6 ? "a" : id <= 12 ? "b" : "c", true)).toList();
        });
        when(catalog.topCategoryNames()).thenReturn(java.util.Map.of("a", "여성복", "b", "남성복", "c", "아동복"));
        modelReturns(List.of());
    }

    private static List<Long> idsOf(HomePageView page) {
        return page.rows().stream().flatMap(r -> r.items().stream()).map(i -> Long.parseLong(i.itemId())).toList();
    }

    @Test
    @DisplayName("다음 쪽은 앞 쪽에서 보여 준 상품을 다시 내지 않는다 — 커서가 그것을 들고 온다")
    void nextPageExcludesShown() {
        popularByCategory();
        HomeComposer composer = composer(HomeComposer.Rules.FULL, 3, 1);

        HomePageView first = composer.compose(USER);
        HomePageView second = composer.compose(USER, HomeCursor.decode(first.nextCursor()));

        assertThat(first.page()).isEqualTo(1);
        assertThat(second.page()).isEqualTo(2);
        assertThat(idsOf(second)).isNotEmpty().doesNotContainAnyElementsOf(idsOf(first));
        assertThat(second.stats().duplicates()).isPositive();   // 커서가 없었다면 다시 나갔을 상품 수
    }

    @Test
    @DisplayName("1쪽 이후에 본 상품의 대분류가 2쪽 첫 행이 된다 — 세션 활동이 다음 쪽 순서를 바꾼다")
    void sessionActivityLeadsNextPage() {
        popularByCategory();
        HomeComposer composer = composer(HomeComposer.Rules.FULL, 3, 1);
        HomePageView first = composer.compose(USER);
        // 1쪽을 본 뒤 c 대분류(아동복) 상품 하나를 봤다
        when(recentActivity.recentItemIds(USER, 8)).thenReturn(List.of(15L));

        HomePageView second = composer.compose(USER, HomeCursor.decode(first.nextCursor()));

        assertThat(second.rows().get(0).id()).isEqualTo("cat:c");
        assertThat(second.rows().get(0).strategy()).isEqualTo("CATEGORY_POPULAR_SESSION");
        assertThat(second.rows().get(0).title()).isEqualTo("아동복 인기");
    }

    @Test
    @DisplayName("시도할 대분류가 다 떨어지면 nextCursor 가 없다")
    void endsWhenCategoriesRunOut() {
        popularByCategory();
        HomeComposer composer = new HomeComposer(recommendations, catalog, recentActivity, impressions,
                HomeComposer.Rules.FULL, 8, 5, 8, 1, 3, 1, 2);
        HomePageView page = composer.compose(USER);
        int pages = 1;
        while (page.nextCursor() != null && pages < 10) {
            page = composer.compose(USER, HomeCursor.decode(page.nextCursor()));
            pages++;
        }
        assertThat(page.nextCursor()).isNull();
        assertThat(pages).isEqualTo(3);   // 1쪽 + 대분류 셋을 쪽당 2행으로 → 2쪽(a·b), 3쪽(c)
    }


    @Test
    @DisplayName("인기 표에 없는 대분류를 봤어도 그 행이 선다 — 대분류 신상품으로 채운다(세션 신호가 조용히 버려지지 않는다)")
    void thinCategoryIsFilledFromNewest() {
        popularByCategory();
        // d 대분류: 인기 표에 하나도 없다. 신상품 30~33 이 있다
        when(catalog.newestInCategory(org.mockito.ArgumentMatchers.eq("d"), anyInt())).thenReturn(List.of(33L, 32L, 31L, 30L));
        when(catalog.newestInCategory(org.mockito.ArgumentMatchers.argThat(c -> !"d".equals(c)), anyInt())).thenReturn(List.of());
        when(catalog.findAll(anyList())).thenAnswer(inv -> {
            List<Long> want = inv.getArgument(0);
            return want.stream().map(id -> card(id, id >= 30 ? "d" : id <= 6 ? "a" : id <= 12 ? "b" : "c", true)).toList();
        });
        HomeComposer composer = composer(HomeComposer.Rules.FULL, 3, 1);
        HomePageView first = composer.compose(USER);
        when(recentActivity.recentItemIds(USER, 8)).thenReturn(List.of(30L));

        HomePageView second = composer.compose(USER, HomeCursor.decode(first.nextCursor()));

        HomePageView.Row row = second.rows().get(0);
        assertThat(row.id()).isEqualTo("cat:d");
        assertThat(row.strategy()).isEqualTo("CATEGORY_POPULAR_SESSION");
        assertThat(row.items()).extracting(HomePageView.Item::reason).containsOnly("대분류 신상품");
        assertThat(row.items()).extracting(HomePageView.Item::itemId).startsWith("33", "32");
    }


    @Test
    @DisplayName("모델이 행을 생성하면 그 행을 쓰고 품절만 생성 뒤에 거른다 — GENPAGE 로 표시한다(#238)")
    void generatedRowsAreUsedAndStockFilteredAfter() {
        popularByCategory();
        when(catalog.findAll(anyList())).thenAnswer(inv -> {
            List<Long> want = inv.getArgument(0);
            // 42 는 품절이다 — 모델은 재고를 모르므로 홈이 거른다
            return want.stream().map(id -> card(id, id <= 6 ? "a" : id <= 12 ? "b" : id >= 40 ? "b" : "c", id != 42L)).toList();
        });
        when(recommendations.generatePageRows(org.mockito.ArgumentMatchers.anyLong(), anyList(), org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.anyCollection(), anyInt(), anyInt()))
                .thenReturn(List.of(new RecommendationFacts.GeneratedRow("b", List.of(41L, 42L, 43L, 44L))));
        HomeComposer composer = composer(HomeComposer.Rules.FULL, 3, 1);
        HomePageView first = composer.compose(USER);

        HomePageView second = composer.compose(USER, HomeCursor.decode(first.nextCursor()));

        assertThat(second.source()).isEqualTo("GENPAGE");
        HomePageView.Row row = second.rows().get(0);
        assertThat(row.strategy()).isEqualTo("GENPAGE");
        assertThat(row.title()).isEqualTo("남성복 추천");
        assertThat(row.items()).extracting(HomePageView.Item::itemId).containsExactly("41", "43", "44");
        assertThat(second.stats().outOfStock()).isEqualTo(1);
        assertThat(second.nextCursor()).isNotNull();   // a·c 가 남았다
    }

    @Test
    @DisplayName("모델이 행을 못 만들면(빈 목록) 규칙 행으로 물러선다")
    void emptyGenerationFallsBackToRules() {
        popularByCategory();
        HomeComposer composer = composer(HomeComposer.Rules.FULL, 3, 1);
        HomePageView first = composer.compose(USER);

        HomePageView second = composer.compose(USER, HomeCursor.decode(first.nextCursor()));

        assertThat(second.rows()).extracting(HomePageView.Row::strategy).doesNotContain("GENPAGE");
    }

}
