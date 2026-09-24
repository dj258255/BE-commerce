package com.beomsu.becommerce.recommendation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.order.PurchaseHistoryFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 구매 이력 입력과 재구매 우선 혼합(#254). 혼합 규칙은 오프라인 {@code hybrid_repeat_then_model} 과 같아야 한다 —
 * 서빙에서 같은 값이 나오는지를 재려는 것이라, 규칙이 다르면 비교가 무의미해진다.
 */
class PurchaseHistoryRecommendationTest {

    private final RecentActivityFacts activity = mock(RecentActivityFacts.class);
    private final PurchaseHistoryFacts purchases = mock(PurchaseHistoryFacts.class);
    private final ModelClient model = mock(ModelClient.class);

    private RecommendationService service(String source, boolean repeatFirst, int resultSize) {
        OverloadGate gate = new OverloadGate(OverloadPolicy.BOUNDED, 24, 100, 4, 50, resultSize, GenerationScope.RANKING, 4, 15);
        return new RecommendationService(activity, model, gate, new ConstraintChecker(new SyntheticAvailability()),
                new RecommendationMetrics(new SimpleMeterRegistry(), gate), ConstraintPolicy.NONE, GenerationScope.RANKING,
                20, purchases, source, repeatFirst, 100, resultSize);
    }

    @Test
    @DisplayName("혼합: 최근 산 것을 중복 없이 먼저, 빈칸을 모델로, k 개에서 자른다")
    void repeatThenModelMatchesOfflineRule() {
        assertThat(RecommendationService.repeatThenModel(List.of(5L, 3L, 5L, 9L), List.of(3L, 7L, 8L, 1L), 4))
                .containsExactly(5L, 3L, 9L, 7L);
        assertThat(RecommendationService.repeatThenModel(List.of(), List.of(3L, 7L), 4)).containsExactly(3L, 7L);
        assertThat(RecommendationService.repeatThenModel(List.of(1L, 2L, 3L, 4L, 5L), List.of(9L), 4))
                .containsExactly(1L, 2L, 3L, 4L);
    }

    @Test
    @DisplayName("purchases 면 구매 이력을 모델에 넣고 활동은 읽지 않는다")
    void purchasesFeedTheModel() {
        when(purchases.recentPurchasedProductIds(1L, 100)).thenReturn(List.of(5L, 3L));
        when(model.recommend(1L, List.of(5L, 3L))).thenReturn(List.of(3L, 7L, 8L));

        RecommendationView view = service(RecommendationService.HISTORY_PURCHASES, true, 4).recommend(1L);

        assertThat(view.items()).containsExactly(5L, 3L, 7L, 8L);
        verify(activity, never()).recentItemIds(anyLong(), anyInt());
    }

    @Test
    @DisplayName("재구매 우선은 활동 이력에서는 꺼진다 — 본 것을 산 것처럼 앞에 두지 않는다")
    void repeatFirstNeedsPurchases() {
        when(activity.recentItemIds(1L, 20)).thenReturn(List.of(5L, 3L));
        when(model.recommend(1L, List.of(5L, 3L))).thenReturn(List.of(7L, 8L));

        RecommendationView view = service(RecommendationService.HISTORY_ACTIVITY, true, 4).recommend(1L);

        assertThat(view.items()).containsExactly(7L, 8L);
        verify(purchases, never()).recentPurchasedProductIds(anyLong(), anyInt());
    }
}
