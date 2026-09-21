package com.beomsu.becommerce.recommendation.internal;

import com.beomsu.becommerce.personalization.RecentActivityFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 추천 서빙 — <b>모델을 부르고, 못 부르면 개인화를 포기한다.</b>
 *
 * <p>이 클래스에 이 실험의 답이 있다: <b>포기하는 지점이 곧 coverage다.</b>
 * 폴백은 실패가 아니라 <b>설계된 응답</b>이다 — 인기 상품은 개인화가 없을 뿐 틀리지 않았다.
 * 그래서 폴백에도 정상 상태(200)로 답하고, 응답의 {@code source}가 어느 쪽인지 밝힌다
 * (화면이 "폴백이면 폴백이라고 보여준다"는 규칙의 서버 쪽 짝 — {@code personalization/web/README.md}).
 *
 * <h2>순서가 중요하다</h2>
 *
 * <ol>
 *   <li>활동을 읽는다 — <b>모델보다 먼저</b>. 과부하일 때 비싼 것은 모델이고, 활동 읽기는 fail-open이라
 *       절대 실패하지 않는다
 *   <li>문을 통과한다({@link OverloadGate}). 여기서 거절되면 <b>모델을 아예 부르지 않는다</b> —
 *       거절의 목적이 모델을 지키는 것이므로, 거절하고도 부르면 아무것도 지키지 못한다
 *   <li>모델을 부른다. 용량을 못 기다리면({@link ModelBusyException}) 그것도 폴백이다
 * </ol>
 *
 * <p>활동이 없는 사용자는 폴백이 아니라 <b>모델을 부른다</b> — 모델이 인기 상품 쪽으로 답할 수 있고,
 * "활동이 없다"와 "모델을 못 불렀다"는 다른 사실이다. 이 둘을 같은 {@code source}로 뭉치면
 * coverage가 실제보다 좋아 보인다.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    /** 폴백으로 내보내는 인기 상품 — 실제 시스템에서는 materialized view나 캐시에서 온다. */
    private static final List<Long> POPULAR_ITEMS = List.of(
            1_000_001L, 1_000_002L, 1_000_003L, 1_000_004L, 1_000_005L, 1_000_006L,
            1_000_007L, 1_000_008L, 1_000_009L, 1_000_010L, 1_000_011L, 1_000_012L);

    private final RecentActivityFacts recentActivity;
    private final ModelClient modelClient;
    private final OverloadGate gate;
    private final RecommendationMetrics metrics;
    private final int contextLimit;

    public RecommendationService(RecentActivityFacts recentActivity,
                                 ModelClient modelClient,
                                 OverloadGate gate,
                                 RecommendationMetrics metrics,
                                 @Value("${app.recommendation.context-limit:20}") int contextLimit) {
        this.recentActivity = recentActivity;
        this.modelClient = modelClient;
        this.gate = gate;
        this.metrics = metrics;
        this.contextLimit = Math.max(contextLimit, 1);
    }

    public RecommendationView recommend(long userId) {
        long startedAt = System.nanoTime();
        // ① 활동 읽기 — 모델을 기다리기 전에 한다. 여기서 실패하면 빈 목록으로 온다(개인화가 약해질 뿐).
        List<Long> recent = recentActivity.recentItemIds(userId, contextLimit);

        // ② 문 — 정책이 거절하면 모델을 아예 부르지 않는다.
        if (!gate.admit()) {
            metrics.fallback("rejected");
            return fallback(userId, "REJECTED", recent.size(), 0L, startedAt);
        }

        // ③ 모델
        long modelStartedAt = System.nanoTime();
        try {
            List<Long> items = modelClient.recommend(userId, recent);
            metrics.servedByModel();
            return new RecommendationView(userId, items, RecommendationView.SOURCE_MODEL, null,
                    recent.size(), elapsedMs(modelStartedAt), elapsedMs(startedAt));
        } catch (ModelBusyException e) {
            // 기다리다 지쳤다 — 모델이 죽은 게 아니라 우리가 못 기다린 것이다.
            metrics.fallback("timeout");
            log.debug("모델 용량 대기 초과 — 폴백한다. userId={}", userId);
            return fallback(userId, "TIMEOUT", recent.size(), elapsedMs(modelStartedAt), startedAt);
        } catch (RuntimeException e) {
            metrics.fallback("failed");
            log.warn("모델 호출 실패 — 폴백한다. userId={} cause={}", userId, e.toString());
            return fallback(userId, "FAILED", recent.size(), elapsedMs(modelStartedAt), startedAt);
        } finally {
            gate.release();
            metrics.modelCallTimer().record(java.time.Duration.ofNanos(System.nanoTime() - modelStartedAt));
        }
    }

    private RecommendationView fallback(long userId, String reason, int contextItems,
                                        long modelMs, long startedAt) {
        return new RecommendationView(userId, POPULAR_ITEMS, RecommendationView.SOURCE_FALLBACK, reason,
                contextItems, modelMs, elapsedMs(startedAt));
    }

    /** 어떤 시점부터 흐른 시간(ms). 인자는 시작 시각이다. */
    private long elapsedMs(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }
}
