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
 */
@Component
public class RecommendationMetrics {

    private final MeterRegistry registry;
    private final Counter servedByModel;
    private final Timer modelCall;
    private final Timer serving;

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

    public Timer modelCallTimer() {
        return modelCall;
    }

    public Timer servingTimer() {
        return serving;
    }
}
