package com.beomsu.becommerce.recommendation.internal;

import com.beomsu.becommerce.personalization.RecentActivityFacts;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Collection;
import java.util.List;
import java.util.Set;

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
 *
 * <p><b>E4(제약 확인 시점)의 테스트가 같은 파일에 있는 이유</b>: 정책이 바뀌어도 폴백 경로는 그대로여야
 * 한다. 확인 시점을 바꾸면서 폴백이 깨지면 두 실험이 서로를 오염시킨다 — 그래서 한 자리에서 본다.
 */
class RecommendationServiceTest {

    private static final long USER = 1L;

    private RecentActivityFacts recentActivity;
    private ModelClient modelClient;
    private SimpleMeterRegistry registry;

    /** 확인 호출 횟수를 세는 래퍼 — "확인이 몇 번 일어났는가"가 정책의 비용이다. */
    private static class CountingAvailability implements AvailabilitySource {
        private final SyntheticAvailability delegate = new SyntheticAvailability();
        int reads;

        @Override
        public Set<Long> unavailableAmong(Collection<Long> itemIds) {
            reads++;
            return delegate.unavailableAmong(itemIds);
        }

        @Override
        public long version() {
            return delegate.version();
        }

        @Override
        public List<Long> knownItemIds() {
            return delegate.knownItemIds();
        }

        boolean consume(long itemId) {
            return delegate.consume(itemId);
        }
    }

    private CountingAvailability availability;

    @BeforeEach
    void setUp() {
        recentActivity = mock(RecentActivityFacts.class);
        modelClient = mock(ModelClient.class);
        registry = new SimpleMeterRegistry();
        availability = new CountingAvailability();
    }

    private RecommendationService service(OverloadPolicy overload, ConstraintPolicy constraint) {
        OverloadGate gate = new OverloadGate(overload, 24, 100, 4, 50);
        RecommendationMetrics metrics = new RecommendationMetrics(registry, gate);
        return new RecommendationService(recentActivity, modelClient, gate,
                new ConstraintChecker(availability), metrics, constraint, 20);
    }

    private RecommendationService service(OverloadPolicy policy) {
        return service(policy, ConstraintPolicy.NONE);
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
        RecommendationMetrics metrics = new RecommendationMetrics(registry, gate);
        RecommendationService service = new RecommendationService(recentActivity, modelClient, gate,
                new ConstraintChecker(availability), metrics, ConstraintPolicy.NONE, 20);

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

    // --- E4: 제약 확인 시점 ---

    /** 확인 대상 풀에서 아무거나 하나 — 모델이 이걸 추천하고, 생성 중에 소진된다. */
    private static final long FLIPPED = 1_000_001L;

    /** 모델이 "추천하는 동안" 그 상품이 팔리지 않게 되는 상황을 만든다 — E4의 핵심 재현이다. */
    private void modelConsumesDuringGeneration() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of());
        when(modelClient.recommend(USER, List.of())).thenAnswer(invocation -> {
            availability.consume(FLIPPED);
            return List.of(FLIPPED, 1_000_002L);
        });
    }

    @Test
    @DisplayName("확인하지 않으면 위반이 그대로 나간다 — 기준선")
    void noneLetsViolationThrough() {
        modelConsumesDuringGeneration();

        RecommendationView view = service(OverloadPolicy.ADMISSION, ConstraintPolicy.NONE).recommend(USER);

        assertThat(view.items()).contains(FLIPPED);
        assertThat(view.violations()).isEqualTo(1);
        assertThat(view.filteredByConstraint()).isZero();
        assertThat(view.snapshotAgeMs()).isNull();   // 확인을 안 했으니 창도 없다
        // 계기(위반 검사) 한 번만 읽는다 — 확인 자체는 없다.
        assertThat(availability.reads).isEqualTo(1);
    }

    @Test
    @DisplayName("생성 전 스냅샷은 생성 중에 바뀐 것을 못 잡는다 — 싼 대신 낡는다")
    void snapshotBeforeGenerationMissesTheChange() {
        modelConsumesDuringGeneration();

        RecommendationView view = service(OverloadPolicy.ADMISSION, ConstraintPolicy.AT_GENERATION_START)
                .recommend(USER);

        // 스냅샷을 뜬 시점엔 팔리고 있었으므로 필터를 통과했고, 응답 시점엔 이미 품절이다.
        assertThat(view.items()).contains(FLIPPED);
        assertThat(view.violations()).isEqualTo(1);
        assertThat(view.changesInWindow()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("생성 후 확인은 생성 중에 바뀐 것을 잡는다 — 비싼 대신 최신이다")
    void checkAfterGenerationCatchesTheChange() {
        modelConsumesDuringGeneration();

        RecommendationView view = service(OverloadPolicy.ADMISSION, ConstraintPolicy.AFTER_GENERATION)
                .recommend(USER);

        assertThat(view.items()).doesNotContain(FLIPPED);
        assertThat(view.items()).containsExactly(1_000_002L);
        assertThat(view.violations()).isZero();
        assertThat(view.filteredByConstraint()).isEqualTo(1);
    }

    @Test
    @DisplayName("응답 직전 확인은 같은 위반 0을 읽기 한 번 더로 산다 — 마지막 확인을 늘려도 창은 안 사라진다")
    void checkAtResponseBuysNothingExtra() {
        modelConsumesDuringGeneration();
        availability.reads = 0;
        RecommendationView atResponse = service(OverloadPolicy.ADMISSION, ConstraintPolicy.AT_RESPONSE)
                .recommend(USER);
        int readsAtResponse = availability.reads;

        modelConsumesDuringGeneration();
        availability.reads = 0;
        RecommendationView afterGeneration = service(OverloadPolicy.ADMISSION, ConstraintPolicy.AFTER_GENERATION)
                .recommend(USER);
        int readsAfterGeneration = availability.reads;

        // 위반은 둘 다 0 — 같은 사실을 두 번 보는 것은 잡는 것을 늘리지 않는다.
        assertThat(atResponse.violations()).isZero();
        assertThat(afterGeneration.violations()).isZero();
        // 대가는 읽기 한 번이다: 확인 2회 + 계기 1회 vs 확인 1회 + 계기 1회.
        assertThat(readsAtResponse).isEqualTo(3);
        assertThat(readsAfterGeneration).isEqualTo(2);
    }

    @Test
    @DisplayName("폴백도 제약을 확인한다 — '폴백이니까 괜찮다'는 근거가 없다")
    void fallbackIsAlsoConstraintChecked() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of());
        when(modelClient.recommend(USER, List.of())).thenThrow(new ModelBusyException("용량 초과"));
        // 폴백이 내보내는 인기 상품 중 하나가 품절됐다 — 그대로 나가면 모든 폴백 사용자가 위반을 받는다.
        availability.consume(1_000_003L);

        RecommendationView view = service(OverloadPolicy.ADMISSION, ConstraintPolicy.AFTER_GENERATION)
                .recommend(USER);

        assertThat(view.source()).isEqualTo(RecommendationView.SOURCE_FALLBACK);
        assertThat(view.items()).doesNotContain(1_000_003L);
        assertThat(view.violations()).isZero();
    }

    @Test
    @DisplayName("모르는 id는 통과가 아니라 거절이다 — 제약 확인이 조용히 무력해지지 않는다")
    void unknownItemIsNotPassed() {
        when(recentActivity.recentItemIds(USER, 20)).thenReturn(List.of());
        when(modelClient.recommend(USER, List.of())).thenReturn(List.of(777_777L, 1_000_001L));

        RecommendationView view = service(OverloadPolicy.ADMISSION, ConstraintPolicy.AFTER_GENERATION)
                .recommend(USER);

        assertThat(view.items()).containsExactly(1_000_001L);
        assertThat(view.filteredByConstraint()).isEqualTo(1);
    }
}
