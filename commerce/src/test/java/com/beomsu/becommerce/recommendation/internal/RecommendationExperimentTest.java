package com.beomsu.becommerce.recommendation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.experiment.ExperimentAssigner;
import com.beomsu.becommerce.order.PurchaseHistoryFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 변형별 동작(#256): 실험군은 구매 이력 + 재구매 우선, 대조군은 설정 그대로. 응답에 실험·변형이 실린다. */
class RecommendationExperimentTest {

    private final RecentActivityFacts activity = mock(RecentActivityFacts.class);
    private final PurchaseHistoryFacts purchases = mock(PurchaseHistoryFacts.class);
    private final ModelClient model = mock(ModelClient.class);

    private RecommendationService service(boolean enabled) {
        MockEnvironment env = new MockEnvironment().withProperty("app.experiments.rec-history.enabled", String.valueOf(enabled))
                .withProperty("app.experiments.rec-history.salt", "t");
        OverloadGate gate = new OverloadGate(OverloadPolicy.BOUNDED, 24, 100, 4, 50, 4, GenerationScope.RANKING, 4, 15);
        return new RecommendationService(activity, model, gate, new ConstraintChecker(new SyntheticAvailability()),
                new RecommendationMetrics(new SimpleMeterRegistry(), gate), ConstraintPolicy.NONE, GenerationScope.RANKING,
                20, purchases, RecommendationService.HISTORY_ACTIVITY, false, 100, 4,
                new ExperimentAssigner(env, new SimpleMeterRegistry()));
    }

    @Test
    @DisplayName("실험군은 구매 이력을 넣고 재구매를 앞에 두며, 대조군은 활동 이력 그대로다")
    void variantsDiffer() {
        when(activity.recentItemIds(anyLong(), anyInt())).thenReturn(List.of(1L));
        when(purchases.recentPurchasedProductIds(anyLong(), anyInt())).thenReturn(List.of(9L));
        when(model.recommend(anyLong(), org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(2L, 3L));
        RecommendationService s = service(true);
        ExperimentAssigner probe = new ExperimentAssigner(new MockEnvironment()
                .withProperty("app.experiments.rec-history.enabled", "true").withProperty("app.experiments.rec-history.salt", "t"),
                new SimpleMeterRegistry());
        long treated = java.util.stream.LongStream.range(1, 100).filter(u -> probe.assign("rec-history", u).treatment())
                .findFirst().orElseThrow();
        long control = java.util.stream.LongStream.range(1, 100).filter(u -> !probe.assign("rec-history", u).treatment())
                .findFirst().orElseThrow();

        RecommendationView t = s.recommend(treated);
        RecommendationView c = s.recommend(control);

        assertThat(t.variant()).isEqualTo(ExperimentAssigner.TREATMENT);
        assertThat(t.experiment()).isEqualTo("rec-history");
        assertThat(t.items()).containsExactly(9L, 2L, 3L);          // 산 것 먼저
        assertThat(c.variant()).isEqualTo(ExperimentAssigner.CONTROL);
        assertThat(c.items()).containsExactly(2L, 3L);              // 활동 이력 · 혼합 없음
        assertThat(s.purchaseHistoryForModel(treated)).containsExactly(9L);   // 다음 쪽도 같은 변형(#270)
        assertThat(s.purchaseHistoryForModel(control)).isNull();
    }

    @Test
    @DisplayName("실험이 꺼져 있으면 응답에 변형이 없다")
    void disabledCarriesNoVariant() {
        when(activity.recentItemIds(anyLong(), anyInt())).thenReturn(List.of());
        when(model.recommend(anyLong(), org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(2L));
        RecommendationView v = service(false).recommend(5L);
        assertThat(v.variant()).isNull();
    }
}
