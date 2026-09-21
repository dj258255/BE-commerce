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
        when(recommendations.popularItemIds()).thenReturn(List.of());
    }

    private HomeComposer composer(HomeComposer.Rules rules, int maxPerCategory, int minItems) {
        return new HomeComposer(recommendations, catalog, recentActivity, impressions, rules, 8, 5, 8,
                minItems, maxPerCategory);
    }

    private static ProductCatalogFacts.ProductCardFacts card(long id, String category, boolean inStock) {
        return new ProductCatalogFacts.ProductCardFacts(id, "상품" + id, 10_000L, "브랜드", "/x.jpg", category, inStock);
    }

    private void modelReturns(List<Long> ids) {
        when(recommendations.recommend(USER))
                .thenReturn(new RecommendationFacts.Recommended(ids, "MODEL", null, 50, 4, "RANKING"));
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
        when(recommendations.popularItemIds()).thenReturn(List.of(1L, 2L, 3L));
        when(catalog.findAll(List.of(999_001L, 999_002L, 999_003L))).thenReturn(List.of());
        when(catalog.findAll(List.of(1L, 2L, 3L))).thenReturn(List.of(card(1, "fashion", true), card(2, "digital", true), card(3, "living", true)));

        HomePageView page = composer(HomeComposer.Rules.FULL, 3, 1).compose(USER);

        // 모델은 MODEL 로 답했지만 화면에 모델 행이 없다 — 그때 FALLBACK 이라고 말해야 정직하다.
        assertThat(page.source()).isEqualTo(HomePageView.SOURCE_FALLBACK);
        assertThat(page.stats().unmatched()).isEqualTo(3);
        assertThat(page.rows()).extracting(HomePageView.Row::id).containsExactly("popular");
    }
}
