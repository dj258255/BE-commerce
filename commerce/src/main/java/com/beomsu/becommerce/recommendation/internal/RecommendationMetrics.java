package com.beomsu.becommerce.recommendation.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/**
 * 추천 서빙의 지표 — E3의 주 지표인 <b>coverage</b>가 여기서 나온다.
 *
 * <p>{@code coverage}를 따로 세는 이유: 이 실험이 재는 것은 "얼마나 빨랐는가"가 아니라
 * <b>"SLO를 지키려고 개인화를 얼마나 포기했는가"</b>다. 응답 시간만 보면 폴백으로 빠르게 답한 것과
 * 모델로 답한 것이 구분되지 않는다.
 *
 * <p><b>폴백 사유를 나눠 센다</b> — {@code rejected}(정책이 거절) / {@code timeout}(모델 용량 대기 초과) /
 * {@code failed}(모델 오류). 사유가 뭉치면 "정책을 바꿔야 하나, 모델을 늘려야 하나"를 지표가
 * 답해 주지 못한다.
 *
 * <p><b>E4의 지표({@code constraint.*})가 따로 있는 이유</b>: 제약 확인은 <b>품질</b>을 사고
 * <b>지연</b>을 낸다. 그 둘을 같은 지표에 섞으면 어느 쪽을 샀는지 알 수 없다. 그래서
 * <ul>
 *   <li>{@code filtered} — 확인이 뺀 개수. 0이면 확인이 할 일이 없었다(위반율 0이 확인 덕이 아니다)</li>
 *   <li>{@code violation} — <b>응답에 실제로 나간</b> 위반. 정책이 스스로 보고하는 값이 아니라
 *       최종 목록을 다시 대조해 센 값이다. 정책 태그를 달아 "어느 정책이 몇 개를 흘렸는가"를 남긴다</li>
 *   <li>{@code audit} — 계기 자체의 비용. 이걸 안 나누면 계기가 정책의 비용처럼 보인다</li>
 * </ul>
 */
@Component
public class RecommendationMetrics {

    private final MeterRegistry registry;
    private final Counter servedByModel;
    private final Timer modelCall;
    private final Timer serving;
    private final Timer check;
    private final Timer audit;

    public RecommendationMetrics(MeterRegistry registry, OverloadGate gate) {
        this.registry = registry;
        this.servedByModel = Counter.builder("recommendation.served")
                .description("모델이 만든 추천으로 응답한 횟수 = coverage의 분자")
                .tag("source", "model")
                .register(registry);
        this.modelCall = Timer.builder("recommendation.model.call")
                .description("모델 호출에 걸린 시간(대기 포함)")
                .register(registry);
        this.serving = Timer.builder("recommendation.serving")
                .description("추천 요청 전체 처리 시간")
                .register(registry);
        this.check = Timer.builder("recommendation.constraint.check")
                .description("제약 확인에 쓴 시간(정책 경로) — E4 후속의 비용 축")
                .register(registry);
        this.audit = Timer.builder("recommendation.constraint.audit")
                .description("위반 검사(실험 계기)에 걸린 시간 — 정책 비용이 아니라 계기 비용이다")
                .register(registry);
        // 지금 모델 쪽에 몇 개가 들어가 있는가 — 정책이 실제로 줄을 끊고 있는지 보는 창이다.
        Gauge.builder("recommendation.in_flight", gate, OverloadGate::inFlight)
                .description("모델 앞에 들어가 있는 요청 수")
                .register(registry);
    }

    public void servedByModel() {
        servedByModel.increment();
    }

    /** 사유별로 따로 센다 — 뭉치면 정책 문제인지 모델 용량 문제인지 지표가 답하지 못한다. */
    public void fallback(String reason) {
        registry.counter("recommendation.fallback", "reason", reason).increment();
    }

    /** 제약 확인이 뺀 개수. 정책 태그로 나눠 "비싼 확인이 실제로 더 많이 막았는가"를 본다. */
    public void constraintFiltered(ConstraintPolicy policy, int removed) {
        if (removed > 0) {
            registry.counter("recommendation.constraint.filtered", "policy", policy.name()).increment(removed);
        }
    }

    /** 응답에 나간 위반(건수). 정책별로 갈라야 "어느 정책이 몇 개를 흘렸는가"가 남는다. */
    public void constraintViolation(ConstraintPolicy policy, int violations) {
        if (violations > 0) {
            registry.counter("recommendation.constraint.violation", "policy", policy.name()).increment(violations);
        }
    }

    public Timer modelCallTimer() {
        return modelCall;
    }

    public Timer servingTimer() {
        return serving;
    }

    public Timer constraintCheckTimer() {
        return check;
    }

    public Timer constraintAuditTimer() {
        return audit;
    }
}

