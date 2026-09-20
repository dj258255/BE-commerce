package com.beomsu.becommerce.reconciliation.cause;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 규칙 분류기가 실제로 얼마나 맞히는지 센다 (ADR-012).
 *
 * <p><b>왜 필요한가</b>: ADR-012는 이 분류기를 "AI가 넘어야 할 기준선"이라고 적었다.
 * 그런데 <b>기준선의 값을 모르면 넘었는지도 모른다.</b> 나중에 AI를 붙였을 때
 * "나아졌다"고 말하려면 지금 규칙이 몇 %를 맞히는지가 먼저 있어야 한다.
 *
 * <p><b>정답은 사람이 만든다.</b> 대사 담당자가 원인을 확정하는 순간 그게 정답 라벨이 된다.
 * 그래서 확정 시점에 분류기를 한 번 더 돌려 제안과 대조한다.
 *
 * <p><b>주의 — 이 지표에는 편향이 있다.</b> 사람이 제안을 <b>보고</b> 확정하므로,
 * 제안에 끌려간 확정(앵커링)이 "적중"으로 집계된다. 즉 이 값은 실제 정확도의
 * <b>상한</b>에 가깝다. 편향 없는 값을 원하면 제안을 감춘 상태에서 확정하는 표본
 * (blind review)이 따로 필요하다 — 12 문서 3-3의 홀드아웃과 같은 발상이다.
 * <b>그 표본은 아직 없다.</b> 그래서 이 수치를 정확도라고 부르지 않고 "일치율"이라고 부른다.
 */
@Component
public class ClassifierAccuracyMetrics {

    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    ClassifierAccuracyMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 사람이 확정한 원인과 분류기 제안을 대조해 집계한다.
     *
     * @param suggestions 확정 시점에 다시 계산한 제안들
     * @param chosen      사람이 실제로 고른 원인
     */
    public void record(List<CauseSuggestion> suggestions, ResolveCause chosen) {
        if (suggestions.isEmpty()) {
            // 제안을 못 낸 경우. 이것도 세야 한다 — 커버리지가 낮으면 정확도가 높아도 쓸모가 없다.
            increment("none", chosen);
            return;
        }
        boolean topMatched = suggestions.get(0).cause() == chosen;
        boolean anyMatched = suggestions.stream().anyMatch(s -> s.cause() == chosen);
        // 1순위 적중과 후보 안 포함을 나눠 센다. 후자만 높으면 "골라주긴 하는데 순서가 틀린" 것이다.
        increment(topMatched ? "top" : anyMatched ? "candidate" : "miss", chosen);
    }

    private void increment(String outcome, ResolveCause chosen) {
        counters.computeIfAbsent(outcome + "|" + chosen, key -> Counter
                .builder("recon.classifier.outcome")
                .description("규칙 분류기 제안과 사람의 확정이 일치했는가. "
                        + "top=1순위 적중, candidate=후보에는 있었음, miss=빗나감, none=제안 없음")
                .tag("outcome", outcome)
                .tag("cause", chosen.name())
                .register(registry)).increment();
    }
}
