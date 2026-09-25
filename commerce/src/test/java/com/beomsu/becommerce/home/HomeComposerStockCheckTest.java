package com.beomsu.becommerce.home;

import com.beomsu.becommerce.home.internal.ImpressionRecorder;
import com.beomsu.becommerce.order.ProductCatalogFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import com.beomsu.becommerce.recommendation.RecommendationFacts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 재고 확인 방식 네 가지(X3, #317)를 고정한다 — 모델이 만든 행을 <b>언제</b> 재고로 거르는가.
 *
 * <p>여기서 지키려는 것은 성능이 아니라 <b>방식마다 무엇을 하는가</b>다. 네 방식은 같은 화면을
 * 다른 정확도로 만들고, 그 대가는 응답의 {@code stats}(stockLookups · stockLookupMs · stockRemoved)에
 * 남아야 한다 — 방식별로 무엇을 얼마에 샀는지 응답만 보고 복원할 수 있어야 한다.
 */
class HomeComposerStockCheckTest {

    private static final long USER = 7L;

    private RecommendationFacts recommendations;
    private ProductCatalogFacts catalog;
    private RecentActivityFacts recentActivity;
    private ImpressionRecorder impressions;

    /** 카드가 품절로 보일 상품 id — 테스트마다 채운다. */
    private final Set<Long> outOfStockCards = new LinkedHashSet<>();

    @BeforeEach
    void setUp() {
        recommendations = mock(RecommendationFacts.class);
        catalog = mock(ProductCatalogFacts.class);
        recentActivity = mock(RecentActivityFacts.class);
        impressions = mock(ImpressionRecorder.class);
        outOfStockCards.clear();
        when(recentActivity.recentItemIds(anyLong(), anyInt())).thenReturn(List.of());
        when(recommendations.recommend(USER))
                .thenReturn(new RecommendationFacts.Recommended(List.of(), "MODEL", null, 50, 4, "RANKING"));
        when(recommendations.experimentOf(USER)).thenReturn(new RecommendationFacts.Experiment(null, null));
        // 인기 표 1~6 은 한 대분류(a)다 — 1쪽이 행을 세우고 2쪽 커서가 남는다.
        List<Long> popular = new ArrayList<>();
        for (long id = 1; id <= 6; id++) {
            popular.add(id);
        }
        when(recommendations.popularItemIds(anyInt()))
                .thenAnswer(inv -> popular.subList(0, Math.min(inv.getArgument(0), popular.size())));
        when(catalog.topCategoryNames()).thenReturn(Map.of("a", "여성복", "b", "남성복"));
        when(catalog.findAll(anyCollection())).thenAnswer(inv -> {
            Collection<Long> want = inv.getArgument(0);
            return want.stream().map(id -> card(id, id <= 6 ? "a" : "b", !outOfStockCards.contains(id))).toList();
        });
    }

    private static ProductCatalogFacts.ProductCardFacts card(long id, String category, boolean inStock) {
        return new ProductCatalogFacts.ProductCardFacts(id, "상품" + id, 10_000L, "브랜드", "/x.jpg", category, inStock);
    }

    private HomeComposer composer(HomeComposer.StockCheck mode) {
        return new HomeComposer(recommendations, catalog, recentActivity, impressions,
                HomeComposer.Rules.FULL, 8, 5, 8, 1, 3, 1, 3, mode);
    }

    private void modelGenerates(Long... ids) {
        when(recommendations.generatePageRows(anyLong(), anyList(), anyCollection(), anyCollection(), anyInt(), anyInt()))
                .thenReturn(List.of(new RecommendationFacts.GeneratedRow("b", List.of(ids))));
    }

    /** 1쪽을 만들어 커서를 얻고, 그 커서로 2쪽(모델 생성 행)을 만든다 — 네 방식이 갈리는 자리다. */
    private HomePageView secondPage(HomeComposer.StockCheck mode) {
        HomeComposer composer = composer(mode);
        HomePageView first = composer.compose(USER);
        return composer.compose(USER, HomeCursor.decode(first.nextCursor()));
    }

    private static List<HomePageView.Item> itemsOf(HomePageView page) {
        return page.rows().stream().filter(r -> r.strategy().equals("GENPAGE"))
                .flatMap(r -> r.items().stream()).toList();
    }

    @Test
    @DisplayName("POST(기본) — 조립할 때 품절을 건다. 별도 조회는 없다")
    void postFiltersOutOfStockAtAssembly() {
        outOfStockCards.add(42L);
        modelGenerates(41L, 42L, 43L);

        HomePageView page = secondPage(HomeComposer.StockCheck.POST);

        assertThat(page.source()).isEqualTo("GENPAGE");
        assertThat(itemsOf(page)).extracting(HomePageView.Item::itemId).containsExactly("41", "43");
        assertThat(page.stats().outOfStock()).isEqualTo(1);
        assertThat(page.stats().stockLookups()).isZero();
        assertThat(page.stats().stockLookupMs()).isZero();
        assertThat(page.stats().stockRemoved()).isEqualTo(1);
        verify(catalog, never()).soldOutProductIds(anyInt());
        verify(catalog, never()).idsInStock(anyCollection());
    }

    @Test
    @DisplayName("NONE — 재고를 보지 않고 모델이 준 상품을 그대로 조립한다(기준선)")
    void noneKeepsSoldOutItems() {
        outOfStockCards.add(42L);
        modelGenerates(41L, 42L, 43L);

        HomePageView page = secondPage(HomeComposer.StockCheck.NONE);

        assertThat(itemsOf(page)).extracting(HomePageView.Item::itemId).containsExactly("41", "42", "43");
        assertThat(page.stats().outOfStock()).isZero();
        assertThat(page.stats().stockLookups()).isZero();
        assertThat(page.stats().stockRemoved()).isZero();
        verify(catalog, never()).soldOutProductIds(anyInt());
        verify(catalog, never()).idsInStock(anyCollection());
    }

    @Test
    @DisplayName("PRE — 모델 호출 전에 품절 id 를 exclude 에 더하고, 조립에서는 거르지 않는다")
    void preExcludesSoldOutBeforeGeneration() {
        when(catalog.soldOutProductIds(anyInt())).thenReturn(Set.of(42L, 99L));
        modelGenerates(41L, 42L, 43L);

        HomePageView page = secondPage(HomeComposer.StockCheck.PRE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> exclude = ArgumentCaptor.forClass(Collection.class);
        verify(recommendations).generatePageRows(anyLong(), anyList(), exclude.capture(), anyCollection(), anyInt(), anyInt());
        assertThat(exclude.getValue()).contains(42L, 99L);
        // 조립은 거르지 않는다 — 모델이 이미 안 준 것을 다시 볼 이유가 없다.
        assertThat(itemsOf(page)).extracting(HomePageView.Item::itemId).containsExactly("41", "42", "43");
        assertThat(page.stats().stockLookups()).isEqualTo(1);
        assertThat(page.stats().stockRemoved()).isEqualTo(2);
        verify(catalog, never()).idsInStock(anyCollection());
    }

    @Test
    @DisplayName("POST_FINAL — 조립 뒤 응답 직전에 한 번 더 보고, 그 사이 품절된 것을 뺀다")
    void postFinalRemovesItemsSoldOutAfterAssembly() {
        // 조립 시점에는 카드가 다 재고 있음이다. 그 뒤 41 이 품절됐다.
        when(catalog.idsInStock(anyCollection())).thenReturn(Set.of(43L, 44L));
        modelGenerates(41L, 43L, 44L);

        HomePageView page = secondPage(HomeComposer.StockCheck.POST_FINAL);

        assertThat(itemsOf(page)).extracting(HomePageView.Item::itemId).containsExactly("43", "44");
        assertThat(page.stats().stockLookups()).isEqualTo(1);
        assertThat(page.stats().stockRemoved()).isEqualTo(1);
        assertThat(page.stats().outOfStock()).isEqualTo(1);
        verify(catalog).idsInStock(anyCollection());
    }
}
