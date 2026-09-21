package com.beomsu.becommerce.recommendation.internal;

import com.beomsu.becommerce.personalization.RecentActivityFacts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 폴백 경로를 고정한다 — <b>거절은 실패가 아니라 설계된 응답</b>이라는 것을 코드가 지키는가.
 *
 * <p>주의해서 보는 것 둘:
 * <ul>
 *   <li>정책이 거절하면 <b>모델을 아예 부르지 않는가</b> — 거절의 목적이 모델을 지키는 것이므로,
 *       거절하고도 부르면 아무것도 지키지 못한다
 *   <li>사유가 갈리는가 — 정책 문제(rejected)와 용량 문제(timeout)는 처방이 다르다
 * </ul>
 */
class RecommendationServiceTest {

    private static final long USER = 1L;

    private RecentActivityFacts recentActivity;
    private ModelClient modelClient;
    private SimpleMeterRegistry registry;
    private RecommendationMetrics metrics;

    @BeforeEach
    void setUp() {
        recentActivity = mock(RecentActivityFacts.class);
        modelClient = mock(ModelClient.class);
        registry = new SimpleMeterRegistry();
    }

    private RecommendationService service(OverloadPolicy policy) {
        OverloadGate gate = new OverloadGate(policy, 1, 100, 4, 50);
        metrics = new RecommendationMetrics(registry, gate);
        return new RecommendationService(recentActivity, modelClient, gate, metrics, 20);
    }

    private double counter(String name, String reason) {
        var counter = registry.find(name).tag("reason", reason).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    @DisplayName("모델이 답하면 source=MODEL이고 폴백 카운터는 오르지 않는다")
    void modelAnswerIsNotFallback() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of(11L, 12L));
        when(modelClient.recommend(USER, List.of(11L, 12L))).thenReturn(List.of(11L, 12L, 13L));

        RecommendationView view = service(OverloadPolicy.BOUNDED).recommend(USER);

        assertThat(view.source()).isEqualTo(RecommendationView.SOURCE_MODEL);
        assertThat(view.fallbackReason()).isNull();
        assertThat(view.items()).containsExactly(11L, 12L, 13L);
        assertThat(view.contextItems()).isEqualTo(2);
        assertThat(registry.get("recommendation.served").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("정책이 거절하면 모델을 부르지 않는다 — 거절의 목적이 모델을 지키는 것이다")
    void rejectionDoesNotTouchTheModel() {
        // 상한 1로 만든 문에서 하나를 붙잡아 두면 다음은 거절된다.
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of());
        OverloadGate gate = new OverloadGate(OverloadPolicy.BOUNDED, 1, 100, 4, 50);
        metrics = new RecommendationMetrics(registry, gate);
        RecommendationService service = new RecommendationService(recentActivity, modelClient, gate, metrics, 20);

        assertThat(gate.admit()).isTrue();   // 다른 요청이 줄을 차지하고 있다
        RecommendationView view = service.recommend(USER);

        assertThat(view.source()).isEqualTo(RecommendationView.SOURCE_FALLBACK);
        assertThat(view.fallbackReason()).isEqualTo("REJECTED");
        // 모델을 아예 안 불렀다 — 거절하고도 부르면 모델은 그대로 무너진다.
        verify(modelClient, never()).recommend(ArgumentMatchers.anyLong(), ArgumentMatchers.anyList());
        assertThat(counter("recommendation.fallback", "rejected")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("모델 용량을 못 기다린 것은 '모델이 실패한 것'과 다른 사유로 센다")
    void busyModelIsTimeoutNotFailure() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of(11L));
        when(modelClient.recommend(USER, List.of(11L))).thenThrow(new ModelBusyException("용량 초과"));

        RecommendationView view = service(OverloadPolicy.BOUNDED).recommend(USER);

        assertThat(view.fallbackReason()).isEqualTo("TIMEOUT");
        assertThat(counter("recommendation.fallback", "timeout")).isEqualTo(1.0);
        assertThat(counter("recommendation.fallback", "failed")).isZero();
    }

    @Test
    @DisplayName("모델이 던진 예외는 폴백으로 흡수한다 — 추천이 죽어도 홈은 살아야 한다")
    void modelFailureFallsBack() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of(11L));
        when(modelClient.recommend(USER, List.of(11L))).thenThrow(new IllegalStateException("모델 오류"));

        RecommendationView view = service(OverloadPolicy.BOUNDED).recommend(USER);

        assertThat(view.source()).isEqualTo(RecommendationView.SOURCE_FALLBACK);
        assertThat(view.fallbackReason()).isEqualTo("FAILED");
        assertThat(view.items()).isNotEmpty();
        assertThat(counter("recommendation.fallback", "failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("활동이 없어도 모델을 부른다 — '활동 없음'과 '모델 못 부름'은 다른 사실이다")
    void noActivityStillCallsModel() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of());
        when(modelClient.recommend(USER, List.of())).thenReturn(List.of(99L));

        RecommendationView view = service(OverloadPolicy.BOUNDED).recommend(USER);

        assertThat(view.source()).isEqualTo(RecommendationView.SOURCE_MODEL);
        assertThat(view.contextItems()).isZero();
    }

    @Test
    @DisplayName("거절해도 문은 새지 않는다 — 다음 요청이 다시 통과할 수 있어야 한다")
    void rejectionReleasesNothingButOtherRequestsPass() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of());
        when(modelClient.recommend(USER, List.of())).thenReturn(List.of(1L));
        RecommendationService service = service(OverloadPolicy.BOUNDED);

        for (int i = 0; i < 50; i++) {
            assertThat(service.recommend(USER).source()).isEqualTo(RecommendationView.SOURCE_MODEL);
        }
    }
}
