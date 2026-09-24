package com.beomsu.becommerce.recommendation.internal;

import com.beomsu.becommerce.experiment.ExperimentAssigner;
import com.beomsu.becommerce.order.PurchaseHistoryFacts;
import com.beomsu.becommerce.personalization.RecentActivityFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * 추천 서빙 — <b>모델을 부르고, 못 부르면 개인화를 포기하고, 나가기 전에 제약을 확인한다.</b>
 *
 * <p>이 클래스에 두 실험의 답이 있다. E3: <b>포기하는 지점이 곧 coverage다.</b> 폴백은 실패가 아니라
 * <b>설계된 응답</b>이다 — 인기 상품은 개인화가 없을 뿐 틀리지 않았다. 그래서 폴백에도 정상 상태(200)로
 * 답하고, 응답의 {@code source}가 어느 쪽인지 밝힌다(화면이 "폴백이면 폴백이라고 보여준다"는 규칙의
 * 서버 쪽 짝). E4: <b>제약 확인 시점이 곧 사는 것과 내주는 것이다</b>
 * ({@link ConstraintPolicy}).
 *
 * <h2>순서가 중요하다</h2>
 *
 * <ol>
 *   <li>활동을 읽는다 — <b>모델보다 먼저</b>. 과부하일 때 비싼 것은 모델이고, 활동 읽기는 fail-open이라
 *       절대 실패하지 않는다
 *   <li>문을 통과한다({@link OverloadGate}). 여기서 거절되면 <b>모델을 아예 부르지 않는다</b> —
 *       거절의 목적이 모델을 지키는 것이므로, 거절하고도 부르면 아무것도 지키지 못한다
 *   <li>모델을 부른다. 용량을 못 기다리면({@link ModelBusyException}) 그것도 폴백이다
 *   <li><b>제약을 확인한다</b>(E4). 정책이 시점을 정하고, 확인은 {@link ConstraintChecker} 하나를 지난다
 * </ol>
 *
 * <p><b>제약 확인은 폴백에도 적용된다.</b> 모델을 못 불러 인기 상품으로 답할 때도 그 상품이 아직
 * 팔리는지 확인해야 한다 — "폴백이니까 괜찮다"는 근거가 없다. 오히려 폴백은 <b>항상 같은 목록</b>이라
 * 그중 하나가 품절되면 모든 폴백 사용자가 위반을 받는다.
 *
 * <p>활동이 없는 사용자는 폴백이 아니라 <b>모델을 부른다</b> — 모델이 인기 상품 쪽으로 답할 수 있고,
 * "활동이 없다"와 "모델을 못 불렀다"는 다른 사실이다. 이 둘을 같은 {@code source}로 뭉치면
 * coverage가 실제보다 좋아 보인다.
 */
@Service
public class RecommendationService {

    private static final Logger log = LoggerFactory.getLogger(RecommendationService.class);

    private final RecentActivityFacts recentActivity;
    private final ModelClient modelClient;
    private final OverloadGate gate;
    private final ConstraintChecker constraintChecker;
    private final RecommendationMetrics metrics;
    private final ConstraintPolicy constraintPolicy;
    private final GenerationScope generationScope;
    private final int contextLimit;
    private final PurchaseHistoryFacts purchases;
    private final String historySource;
    private final boolean repeatFirst;
    private final int purchaseLimit;
    private final int resultSize;
    private final ExperimentAssigner experiments;

    /**
     * A/B 실험 이름(#256). 켜면 실험군은 구매 이력 + 재구매 우선(ADR-060), 대조군은 위 설정 그대로다.
     * 설정은 {@code app.experiments.rec-history.*}.
     */
    static final String EXPERIMENT = "rec-history";

    /** 테스트와 예전 설정용: 활동 이력을 넣고 혼합하지 않는다. */
    public RecommendationService(RecentActivityFacts recentActivity,
                                 ModelClient modelClient,
                                 OverloadGate gate,
                                 ConstraintChecker constraintChecker,
                                 RecommendationMetrics metrics,
                                 ConstraintPolicy constraintPolicy,
                                 GenerationScope generationScope,
                                 int contextLimit) {
        this(recentActivity, modelClient, gate, constraintChecker, metrics, constraintPolicy, generationScope, contextLimit,
                null, HISTORY_ACTIVITY, false, 100, 12, null);
    }

    /**
     * @param historySource {@code activity}(온라인 컨텍스트의 조회·클릭) 또는 {@code purchases}(결제 완료 주문, #254)
     * @param repeatFirst   최근 산 것을 중복 없이 먼저 두고 빈칸을 모델로 채운다. {@code purchases} 일 때만 뜻이 있다
     */
    @org.springframework.beans.factory.annotation.Autowired
    public RecommendationService(RecentActivityFacts recentActivity,
                                 ModelClient modelClient,
                                 OverloadGate gate,
                                 ConstraintChecker constraintChecker,
                                 RecommendationMetrics metrics,
                                 @Value("${app.recommendation.constraint-policy:NONE}") ConstraintPolicy constraintPolicy,
                                 @Value("${app.recommendation.generation.scope:RANKING}") GenerationScope generationScope,
                                 @Value("${app.recommendation.context-limit:20}") int contextLimit,
                                 PurchaseHistoryFacts purchases,
                                 @Value("${app.recommendation.history-source:activity}") String historySource,
                                 @Value("${app.recommendation.repeat-first:false}") boolean repeatFirst,
                                 @Value("${app.recommendation.purchase-history-limit:100}") int purchaseLimit,
                                 @Value("${app.recommendation.result-size:12}") int resultSize,
                                 ExperimentAssigner experiments) {
        this.experiments = experiments;
        this.purchases = purchases;
        this.historySource = HISTORY_PURCHASES.equals(historySource) && purchases != null ? HISTORY_PURCHASES : HISTORY_ACTIVITY;
        this.repeatFirst = repeatFirst && HISTORY_PURCHASES.equals(this.historySource);
        if (repeatFirst && !this.repeatFirst) {
            log.warn("repeat-first 는 history-source=purchases 에서만 뜻이 있다 — 끈다");
        }
        this.purchaseLimit = Math.max(purchaseLimit, 1);
        this.resultSize = Math.max(resultSize, 1);
        this.recentActivity = recentActivity;
        this.modelClient = modelClient;
        this.gate = gate;
        this.constraintChecker = constraintChecker;
        this.metrics = metrics;
        this.constraintPolicy = constraintPolicy;
        this.generationScope = generationScope;
        this.contextLimit = Math.max(contextLimit, 1);
        log.info("제약 확인 정책={} 생성 범위={} (E4·E5) 이력={} 재구매 우선={}", constraintPolicy, generationScope,
                this.historySource, this.repeatFirst);
    }

    static final String HISTORY_ACTIVITY = "activity";
    static final String HISTORY_PURCHASES = "purchases";

    private ExperimentAssigner.Assignment assignment(long userId) {
        return experiments == null ? new ExperimentAssigner.Assignment(EXPERIMENT, null) : experiments.assign(EXPERIMENT, userId);
    }

    /** 실험군이면 구매 이력, 아니면 설정값. 실험군의 설정은 코드에 고정한다 — 변형이 무엇인지가 설정마다 달라지면 결과를 못 읽는다. */
    private List<Long> history(long userId, ExperimentAssigner.Assignment assignment) {
        if (assignment.treatment() && purchases != null) {
            return purchases.recentPurchasedProductIds(userId, purchaseLimit);
        }
        return history(userId);
    }

    private boolean repeatFirst(ExperimentAssigner.Assignment assignment) {
        return assignment.treatment() ? purchases != null : repeatFirst;
    }

    private List<Long> history(long userId) {
        if (HISTORY_PURCHASES.equals(historySource)) {
            return purchases.recentPurchasedProductIds(userId, purchaseLimit);
        }
        return recentActivity.recentItemIds(userId, contextLimit);
    }

    /**
     * 재구매 우선 혼합(#254). 최근 산 것을 중복 없이 먼저 두고 빈칸을 모델 결과로 채운다. 오프라인의
     * {@code hybrid_repeat_then_model}(train_genpage.py)과 같은 규칙이다 — 서빙에서도 같은 값이 나오는지 재려는 것이다.
     */
    static List<Long> repeatThenModel(List<Long> purchasesNewestFirst, List<Long> model, int k) {
        java.util.LinkedHashSet<Long> out = new java.util.LinkedHashSet<>();
        for (Long p : purchasesNewestFirst) {
            if (out.size() >= k) {
                break;
            }
            out.add(p);
        }
        for (Long m : model) {
            if (out.size() >= k) {
                break;
            }
            out.add(m);
        }
        return List.copyOf(out);
    }

    public RecommendationView recommend(long userId) {
        long startedAt = System.nanoTime();

        // ① 확인을 미리 하는 정책이면 여기서 넓게 읽는다(풀 전체). 모델 출력을 아직 모르기 때문이다.
        // 이 읽기도 확인 비용이므로 따로 재 둔다 — 모델 호출 뒤의 확인과 합쳐서 checkMs 로 보고한다.
        long preCheckStartedAt = System.nanoTime();
        ConstraintChecker.Snapshot snapshot =
                constraintPolicy.snapshotsBeforeGeneration() ? constraintChecker.snapshot() : null;
        long preCheckNanos = System.nanoTime() - preCheckStartedAt;

        // ② 활동 읽기 — 모델을 기다리기 전에 한다. 여기서 실패하면 빈 목록으로 온다(개인화가 약해질 뿐).
        // E5 의 구간 계기: 이 시간이 예산의 "컨텍스트" 몫이다.
        long contextStartedAt = System.nanoTime();
        ExperimentAssigner.Assignment assignment = assignment(userId);
        List<Long> recent = history(userId, assignment);
        long contextNanos = System.nanoTime() - contextStartedAt;

        // ③ 문 — 정책이 거절하면 모델을 아예 부르지 않는다.
        if (!gate.admit()) {
            metrics.fallback("rejected");
            return finish(userId, ItemPool.POPULAR, RecommendationView.SOURCE_FALLBACK, "REJECTED",
                    recent.size(), contextNanos, 0L, snapshot, preCheckNanos, startedAt, assignment);
        }

        // ④ 모델
        long modelStartedAt = System.nanoTime();
        try {
            List<Long> items = callModel(userId, recent);
            gate.completed();                               // 처리량 관측(#264). 대기 초과·실패는 모델을 쓰지 못했으니 세지 않는다
            if (repeatFirst(assignment)) {
                items = repeatThenModel(recent, items, resultSize);
            }
            metrics.servedByModel();
            return finish(userId, items, RecommendationView.SOURCE_MODEL, null,
                    recent.size(), contextNanos, elapsedMs(modelStartedAt), snapshot, preCheckNanos, startedAt, assignment);
        } catch (ModelBusyException e) {
            // 기다리다 지쳤다 — 모델이 죽은 게 아니라 우리가 못 기다린 것이다.
            metrics.fallback("timeout");
            log.debug("모델 용량 대기 초과 — 폴백한다. userId={}", userId);
            return finish(userId, ItemPool.POPULAR, RecommendationView.SOURCE_FALLBACK, "TIMEOUT",
                    recent.size(), contextNanos, elapsedMs(modelStartedAt), snapshot, preCheckNanos, startedAt, assignment);
        } catch (RuntimeException e) {
            metrics.fallback("failed");
            log.warn("모델 호출 실패 — 폴백한다. userId={} cause={}", userId, e.toString());
            return finish(userId, ItemPool.POPULAR, RecommendationView.SOURCE_FALLBACK, "FAILED",
                    recent.size(), contextNanos, elapsedMs(modelStartedAt), snapshot, preCheckNanos, startedAt, assignment);
        } finally {
            gate.release();
            metrics.modelCallTimer().record(Duration.ofNanos(System.nanoTime() - modelStartedAt));
        }
    }

    /**
     * 모델을 부른다. {@code DURING_GENERATION}이면 <b>제약을 넘겨</b> 생성 중에 막는다.
     *
     * <p><b>못 받는 구현이면 넘기지 않는다.</b> 넘기고 넘겼다고 믿는 것이 가장 나쁘다 — 위반이
     * 조용히 나간다. 그래서 {@link ModelClient#supportsConstrainedGeneration()}을 보고,
     * 못 하면 사후 필터로 <b>내려앉되 그 사실을 지표로 남긴다</b>. 조용한 강등은 강등이 아니라 사고다.
     */
    private List<Long> callModel(long userId, List<Long> recent) {
        if (!constraintPolicy.blocksDuringGeneration()) {
            return modelClient.recommend(userId, recent);
        }
        if (!modelClient.supportsConstrainedGeneration()) {
            metrics.constrainedGenerationUnsupported();
            return modelClient.recommend(userId, recent);
        }
        // 후보 하나를 물을 때마다 한 번 읽는다. 이 수가 곧 이 방식의 비용이고, 위반율에 비례해 는다.
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        try {
            return modelClient.recommend(userId, recent, itemId -> {
                asked.incrementAndGet();
                return constraintChecker.isAvailable(itemId);
            });
        } finally {
            metrics.constrainedGenerationLookups(asked.get());
        }
    }

    /**
     * 제약 확인 → 계기 → 응답 조립. <b>정책이 여기서 갈린다.</b>
     *
     * <p>확인에 쓴 스냅샷을 잃지 않고 {@code snapshotAgeMs}·{@code changesInWindow}로 내보내는 이유:
     * 위반율만 보면 <b>"확인이 잘 해서 낮은 것"과 "바뀐 게 없어서 낮은 것"이 구분되지 않는다.</b>
     */
    private RecommendationView finish(long userId, List<Long> items, String source, String reason,
                                      int contextItems, long contextNanos, long modelMs,
                                      ConstraintChecker.Snapshot beforeGeneration, long preCheckNanos,
                                      long startedAt,
                                      ExperimentAssigner.Assignment assignment) {
        ConstraintChecker.Snapshot used = beforeGeneration;
        int filtered = 0;
        List<Long> finalItems = items;

        // 확인 구간만 따로 잰다 — servingMs 에는 모델 지연(50ms)이 들어 있어 확인 비용이 그 안에 묻힌다.
        long checkStartedAt = System.nanoTime();
        switch (constraintPolicy) {
            case NONE -> {
                // 아무것도 하지 않는다 — 이것이 기준선이다.
            }
            case AT_GENERATION_START -> {
                ConstraintChecker.Filtered result = constraintChecker.filter(items, beforeGeneration);
                finalItems = result.items();
                filtered = result.removed();
            }
            case AFTER_GENERATION -> {
                ConstraintChecker.Filtered result = constraintChecker.filterNow(items);
                used = result.snapshot();
                finalItems = result.items();
                filtered = result.removed();
            }
            case AT_RESPONSE -> {
                // 한 번 더 확인한다. 모델 반환 뒤에 일어난 일까지 잡으려는 것이고, 대가는 읽기 한 번이다.
                ConstraintChecker.Filtered first = constraintChecker.filterNow(items);
                ConstraintChecker.Filtered second = constraintChecker.filterNow(first.items());
                used = second.snapshot();
                finalItems = second.items();
                filtered = first.removed() + second.removed();
            }
            case DURING_GENERATION -> {
                // 생성 중에 막았어도 <b>출력을 한 번 더 본다.</b> 두 가지 이유다.
                //   ① 모델이 제약을 못 받아 내려앉았을 수 있다 — 그러면 이 필터가 유일한 보호다
                //   ② 받았더라도 지켰는지는 모델의 자기 보고다. 이 저장소는 모델 출력을 믿지 않는다
                // 모델이 제대로 막았으면 여기서 0 개가 빠지고, 그 0 이 곧 검증이다.
                ConstraintChecker.Filtered result = constraintChecker.filterNow(items);
                used = result.snapshot();
                finalItems = result.items();
                filtered = result.removed();
            }
        }

        long policyMs = elapsedMs(startedAt);
        long postCheckNanos = System.nanoTime() - checkStartedAt;
        // 확인 비용은 사전 스냅샷(모델 호출 전)과 여기(모델 호출 뒤) 둘로 나뉜다. 합쳐서 보고한다 —
        // 사전 읽기를 빼면 AT_GENERATION_START 가 공짜처럼 보여 교환비 계산이 틀어진다(실제로 그랬다).
        long checkMs = (preCheckNanos + postCheckNanos) / 1_000_000;
        metrics.constraintCheckTimer().record(Duration.ofNanos(preCheckNanos + postCheckNanos));

        // 계기 — 정책과 무관하게 <b>최종 목록</b>을 다시 사실과 대조한다. 정책의 자기 보고가 아니다.
        long auditStartedAt = System.nanoTime();
        Set<Long> violations = constraintChecker.auditViolations(finalItems);
        long auditMs = elapsedMs(auditStartedAt);
        metrics.constraintAuditTimer().record(Duration.ofNanos(System.nanoTime() - auditStartedAt));

        metrics.constraintFiltered(constraintPolicy, filtered);
        metrics.constraintViolation(constraintPolicy, violations.size());

        return new RecommendationView(userId, finalItems, source, reason, contextItems,
                contextNanos / 1_000_000.0, modelMs, policyMs,
                checkMs, generationScope.name(), constraintPolicy.name(), filtered,
                used == null ? null : constraintChecker.ageMs(used),
                used == null ? null : constraintChecker.changesSince(used),
                violations.size(), auditMs, assignment.experiment(), assignment.variant());
    }

    /** 어떤 시점부터 흐른 시간(ms). 인자는 시작 시각이다. */
    private long elapsedMs(long fromNanos) {
        return (System.nanoTime() - fromNanos) / 1_000_000;
    }
}
