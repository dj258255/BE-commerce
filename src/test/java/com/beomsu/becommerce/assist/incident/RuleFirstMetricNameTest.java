package com.beomsu.becommerce.assist.incident;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림이 보는 지표 이름과 태그가 실제로 그렇게 나오는지 지킨다.
 *
 * <p><b>왜 필요한가</b>: 이름이 한 글자만 달라도 알림은 <b>영원히 안 울린다.</b> 그런데
 * 안 울리는 알림은 잘 도는 알림과 화면에서 구별되지 않는다. 이 프로젝트가 "조용히 멈추는 것"을
 * 잡겠다고 만든 알림이 정작 자기가 조용히 멈춰 있으면 안 된다.
 *
 * <p>보는 곳: {@code monitoring/alert-rules.yml} 의 {@code AssistIncidentModelSilent}.
 * 프로메테우스에서 {@code assist.incident} 는 {@code assist_incident_outcome_total} 이 된다.
 */
@DisplayName("장애 분석 지표 이름 — 알림이 보는 그 이름인지")
class RuleFirstMetricNameTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private RuleFirstIncidentAnalyzer analyzerWith(Optional<IncidentDiagnosis> modelAnswer) {
        IncidentAnalysisPort model = new IncidentAnalysisPort() {
            @Override
            public Optional<IncidentDiagnosis> diagnose(String logs) {
                return modelAnswer;
            }

            @Override
            public String name() {
                return "stub";
            }
        };
        return new RuleFirstIncidentAnalyzer(new RuleBasedIncidentAnalyzer(), model, registry);
    }

    private double count(String outcome) {
        var c = registry.find(RuleFirstIncidentAnalyzer.METRIC).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("규칙이 답하면 by_rule 로 센다")
    void countsRuleAnswers() {
        // 규칙이 아는 문장이다.
        analyzerWith(Optional.empty()).diagnose(
                "HikariPool-1 - Connection is not available, request timed out after 2003ms");

        assertThat(count("by_rule")).isEqualTo(1);
    }

    @Test
    @DisplayName("규칙이 기권하고 모델도 답을 못 내면 model_silent 로 센다")
    void countsSilentModel() {
        analyzerWith(Optional.empty()).diagnose("아무 규칙에도 안 걸리는 알 수 없는 줄");

        assertThat(count("model_silent")).isEqualTo(1);
        assertThat(count("by_model")).isZero();
    }

    @Test
    @DisplayName("모델 인용이 원문에 없으면 model_ungrounded 로 센다")
    void countsUngroundedModel() {
        analyzerWith(Optional.of(new IncidentDiagnosis(
                IncidentCause.DB_TIMEOUT, "원문에 없는 인용")))
                .diagnose("아무 규칙에도 안 걸리는 알 수 없는 줄");

        assertThat(count("model_ungrounded")).isEqualTo(1);
    }
}
