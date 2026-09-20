package com.beomsu.becommerce.assist.incident;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 규칙이 먼저 답하고, <b>규칙이 기권한 자리에만</b> 모델을 부른다.
 *
 * <p><b>왜 양자택일이 아닌가.</b> 실제 로그 12건으로 재니 둘의 성질이 달랐다.
 * <ul>
 *   <li>규칙 — 맞음 7 · 기권 5 · <b>틀림 0</b>. 아는 것만 답하고 모르면 입을 다문다</li>
 *   <li>모델 — 맞음 11 · 기권 0 · <b>틀림 1</b>. 더 많이 맞히는 대신 <b>틀린 것도 확신 있게 답한다</b></li>
 * </ul>
 *
 * <p>모델을 통째로 쓰면 규칙이 이미 확실히 답하던 7건까지 모델의 오답 위험에 노출된다. 규칙을
 * 그대로 두고 <b>기권한 5건만</b> 모델에 넘기면 그 7건은 손대지 않은 채 답을 4건 더 얻는다.
 * 대신 오답 1건이 새로 생긴다 — 그래서 <b>확정은 사람이 한다</b>는 전제가 여기서도 유지된다.
 *
 * <p><b>출처를 표시한다.</b> 규칙이 낸 답과 모델이 낸 답은 신뢰도가 다르므로 화면에서 구분되어야
 * 한다. {@link IncidentDiagnosis#source()} 가 그것을 들고 다닌다.
 */
@Component
@ConditionalOnProperty(name = "app.assist.incident-provider", havingValue = "rule-first")
public class RuleFirstIncidentAnalyzer implements IncidentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(RuleFirstIncidentAnalyzer.class);

    /** 알림이 보는 이름. {@code assist.residual.outcome} 과 규칙을 맞춘다. */
    public static final String METRIC = "assist.incident.outcome";

    private final RuleBasedIncidentAnalyzer rule;
    private final IncidentAnalysisPort model;
    private final EvidenceGroundingGuard grounding = new EvidenceGroundingGuard();
    /**
     * 누가 답했는지를 센다. 이 지표가 없으면 <b>모델이 죽어도 화면은 멀쩡해 보인다</b> —
     * 규칙이 답한 건은 그대로 나오고, 기권한 건은 원래도 비어 있었기 때문이다.
     */
    private final MeterRegistry registry;

    /**
     * <b>{@code @Autowired} 가 붙은 이유</b>: 아래 테스트용 생성자와 둘이라, 표시가 없으면 스프링이
     * 어느 쪽을 쓸지 못 정하고 기본 생성자를 찾다 {@code NoSuchMethodException} 으로 죽는다.
     * 컨텍스트를 띄우지 않는 단위 테스트에서는 안 걸리고 통합 테스트에서만 드러났다.
     *
     * <p>두 구현을 직접 조립한다. 각자 {@code @ConditionalOnProperty} 로 자기 이름일 때만 빈이
     * 되므로, {@code rule-first} 에서는 둘 다 빈이 아니다. 주입받을 수 없어 여기서 만든다.
     */
    @Autowired
    public RuleFirstIncidentAnalyzer(
            @Value("${app.assist.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${app.assist.ollama.incident-model:qwen3:8b}") String model,
            @Value("${app.assist.ollama.incident-timeout-seconds:30}") long timeoutSeconds,
            MeterRegistry registry) {
        this(new RuleBasedIncidentAnalyzer(), new OllamaIncidentAnalyzer(baseUrl, model, timeoutSeconds),
                registry);
    }

    RuleFirstIncidentAnalyzer(RuleBasedIncidentAnalyzer rule, IncidentAnalysisPort model,
                              MeterRegistry registry) {
        this.rule = rule;
        this.model = model;
        this.registry = registry;
    }

    private void count(String outcome) {
        registry.counter(METRIC, "outcome", outcome).increment();
    }

    @Override
    public String name() {
        return "rule-first(" + model.name() + ")";
    }

    @Override
    public Optional<IncidentDiagnosis> diagnose(String logText) {
        Optional<IncidentDiagnosis> byRule = rule.diagnose(logText);
        if (byRule.isPresent() && byRule.get().cause() != IncidentCause.UNKNOWN) {
            count("by_rule");
            return byRule;
        }

        Optional<IncidentDiagnosis> byModel = model.diagnose(logText);
        // 인용이 원문에 없으면 버린다. 규칙이 이미 기권한 자리이므로 여기서 버려도 잃는 게 없다.
        if (byModel.isPresent() && !grounding.grounded(byModel.get(), logText)) {
            count("model_ungrounded");
            log.warn("모델 진단을 버렸다 — 인용이 원문에 없다: {}", byModel.get().cause());
            return Optional.empty();
        }
        if (byModel.isEmpty()) {
            // 모델이 기권했거나 <b>모델 서버가 죽었다.</b> 둘은 여기서 구별되지 않지만,
            // 이 값이 계속 오르면 규칙이 기권한 자리가 통째로 비고 있다는 뜻이다.
            count("model_silent");
            return Optional.empty();
        }
        count("by_model");
        return byModel;
    }
}
