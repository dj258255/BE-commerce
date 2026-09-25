package com.beomsu.becommerce.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.personalization.RecentActivityFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts.RecentActivity;
import com.beomsu.becommerce.recommendation.internal.GenPagePageClient;
import com.beomsu.becommerce.recommendation.internal.GenPagePageClient.SessionEvent;
import com.beomsu.becommerce.recommendation.internal.ItemPoolSource;
import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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

    @SuppressWarnings("unchecked")
    private static RecommendationFacts rich(RecommendationService service, RecentActivityFacts activity, GenPagePageClient client) {
        ObjectProvider<GenPagePageClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new RecommendationFacts(service, mock(ItemPoolSource.class), provider, new SimpleMeterRegistry(), "purchases",
                activity, RecommendationFacts.GenPageSession.RICH, 20);
    }

    @Test
    @DisplayName("RICH 면 세션을 종류 · 시각과 함께 오래된 것부터 보내고, history 에는 구매만 둔다(X5, #328)")
    @SuppressWarnings("unchecked")
    void richSendsTypedSessionOldestFirst() {
        RecommendationService service = mock(RecommendationService.class);
        when(service.purchaseHistoryForModel(7L)).thenReturn(null);   // 기본 설정 — 구매 이력을 쓰지 않는 사용자
        RecentActivityFacts activity = mock(RecentActivityFacts.class);
        Instant t1 = Instant.parse("2026-09-26T01:00:00Z");
        Instant t2 = Instant.parse("2026-09-26T01:05:00Z");
        when(activity.recentActivities(7L, 20)).thenReturn(List.of(   // 저장소는 최근 것부터
                new RecentActivity(2L, "CLICK", t2), new RecentActivity(1L, "VIEW", t1)));
        GenPagePageClient client = mock(GenPagePageClient.class);

        rich(service, activity, client).generatePageRows(7L, List.of(2L, 1L), List.of(9L), List.of(), 3, 8);

        ArgumentCaptor<List<SessionEvent>> session = ArgumentCaptor.forClass(List.class);
        verify(client).generate(eq(List.of()), session.capture(), any(Instant.class), eq(List.of(9L)), eq(List.of()),
                eq(3), eq(8));
        assertThat(session.getValue()).containsExactly(new SessionEvent(1L, "VIEW", t1), new SessionEvent(2L, "CLICK", t2));
    }

    @Test
    @DisplayName("RICH 에서 구매 이력을 쓰는 사용자면 history 는 구매다 — 세션을 id 로 섞지 않는다(X5, #328)")
    void richKeepsPurchasesAsHistory() {
        RecommendationService service = mock(RecommendationService.class);
        when(service.purchaseHistoryForModel(7L)).thenReturn(List.of(10L, 11L));
        RecentActivityFacts activity = mock(RecentActivityFacts.class);
        when(activity.recentActivities(7L, 20)).thenReturn(List.of());
        GenPagePageClient client = mock(GenPagePageClient.class);

        rich(service, activity, client).generatePageRows(7L, List.of(1L), List.of(), List.of(), 3, 8);

        verify(client).generate(eq(List.of(10L, 11L)), eq(List.of()), any(Instant.class), any(), any(), eq(3), eq(8));
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

    @Test
    @DisplayName("모델 자리가 없으면 빈 목록을 주고 실패가 아닌 busy 로 센다(#271)")
    void busyIsEmptyAndCountedSeparately() {
        GenPagePageClient client = mock(GenPagePageClient.class);
        when(client.generate(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new com.beomsu.becommerce.recommendation.internal.ModelBusyException("자리 없음"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(facts(client, registry).generatePageRows(List.of(1L), List.of(), List.of(), 3, 8)).isEmpty();
        assertThat(registry.get("recommendation.genpage.page").tag("result", "busy").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recommendation.genpage.page").tag("result", "failed").counter().count()).isZero();
    }
}
