package com.beomsu.becommerce.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.recommendation.internal.GenPagePageClient;
import com.beomsu.becommerce.recommendation.internal.ItemPoolSource;
import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RecommendationFactsGenPageTest {

    @SuppressWarnings("unchecked")
    private static RecommendationFacts facts(GenPagePageClient client, SimpleMeterRegistry registry) {
        ObjectProvider<GenPagePageClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new RecommendationFacts(mock(RecommendationService.class), mock(ItemPoolSource.class), provider, registry);
    }

    @SuppressWarnings("unchecked")
    private static RecommendationFacts facts(RecommendationService service, String pageHistory) {
        ObjectProvider<GenPagePageClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(mock(GenPagePageClient.class));
        return new RecommendationFacts(service, mock(ItemPoolSource.class), provider, new SimpleMeterRegistry(), pageHistory);
    }

    @Test
    @DisplayName("추천이 구매 이력을 쓰지 않는 사용자면 다음 쪽도 지금처럼 세션을 넣는다(#270)")
    void activityUsersKeepSession() {
        RecommendationService service = mock(RecommendationService.class);
        when(service.purchaseHistoryForModel(7L)).thenReturn(null);

        assertThat(facts(service, "purchases").pageHistory(7L, List.of(1L, 2L))).containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("구매 이력을 쓰는 사용자면 다음 쪽도 구매를 넣는다 — 학습과 같은 입력(#270)")
    void purchaseUsersGetPurchases() {
        RecommendationService service = mock(RecommendationService.class);
        when(service.purchaseHistoryForModel(7L)).thenReturn(List.of(10L, 11L));

        assertThat(facts(service, "purchases").pageHistory(7L, List.of(1L, 2L))).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("session-then-purchases 면 세션을 가장 최근 토큰으로 앞에 붙인다 — 세션이 비면 구매만(#270)")
    void sessionThenPurchases() {
        RecommendationService service = mock(RecommendationService.class);
        when(service.purchaseHistoryForModel(7L)).thenReturn(List.of(10L, 11L));
        RecommendationFacts facts = facts(service, "session-then-purchases");

        assertThat(facts.pageHistory(7L, List.of(1L, 2L))).containsExactly(1L, 2L, 10L, 11L);
        assertThat(facts.pageHistory(7L, List.of())).containsExactly(10L, 11L);
    }

    @Test
    @DisplayName("모델이 꺼져 있으면 빈 목록이다 — 홈은 규칙 행을 쓴다")
    void disabledIsEmpty() {
        assertThat(facts(null, new SimpleMeterRegistry()).generatePageRows(List.of(), List.of(), List.of(), 3, 8)).isEmpty();
    }

    @Test
    @DisplayName("모델 서버가 실패하면 예외 대신 빈 목록을 주고 실패를 센다")
    void failureIsEmptyAndCounted() {
        GenPagePageClient client = mock(GenPagePageClient.class);
        when(client.generate(any(), any(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("모델 서버가 죽었다"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(facts(client, registry).generatePageRows(List.of(1L), List.of(), List.of(), 3, 8)).isEmpty();
        assertThat(registry.get("recommendation.genpage.page").tag("result", "failed").counter().count()).isEqualTo(1.0);
    }
}
