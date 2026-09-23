package com.beomsu.becommerce.monitoring;

import com.beomsu.becommerce.RepoRoot;
import com.beomsu.becommerce.assist.incident.RuleFirstIncidentAnalyzer;
import com.beomsu.becommerce.assist.narrative.TimelineNarrativeService;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 알림 파일이 찾는 지표 이름이 <b>실제로 노출되는 이름과 같은지</b> 대조한다.
 *
 * <p><b>왜 필요한가</b>: 자바 쪽 이름을 테스트로 고정해도 그것만으로는 부족하다. 마이크로미터가
 * {@code assist.narrative} 를 프로메테우스에 내보낼 때 붙이는 이름은 {@code assist_narrative_total}
 * 이지 {@code assist_narrative_outcome_total} 이 아니다. 카운터 이름에 {@code outcome} 이 들어가려면
 * 태그가 아니라 <b>이름 자체</b>에 있어야 한다.
 *
 * <p>실제로 이 자리에서 틀렸다. 기존 카운터가 {@code assist.residual.outcome} 이라
 * {@code assist_residual_outcome_total} 로 나가는 것을 보고, 새 카운터를 {@code assist.narrative} 로
 * 만들어 놓고 알림은 {@code assist_narrative_outcome_total} 을 찾게 썼다. 그 알림은 영원히 안 울린다.
 *
 * <p><b>안 울리는 알림은 잘 도는 알림과 화면에서 구별되지 않는다.</b> 그래서 이 대조를 사람 눈이
 * 아니라 테스트에 맡긴다.
 */
@DisplayName("알림 지표 이름 계약 — 알림이 찾는 이름이 실제로 나오는지")
class AlertMetricNameContractTest {

    private static final Path RULES = RepoRoot.resolve("monitoring/alert-rules.yml");

    /** 애플리케이션이 쓰는 카운터. 여기 적힌 것이 곧 노출되는 이름의 근거다. */
    private static final List<String> COUNTERS = List.of(
            "assist.residual.outcome",
            "assist.residual.accepted",
            TimelineNarrativeService.METRIC,
            RuleFirstIncidentAnalyzer.METRIC);

    private PrometheusMeterRegistry exposeAll() {
        var registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        // 카운터는 한 번도 증가하지 않으면 스크레이프에 안 나온다. 그래서 먼저 올린다.
        COUNTERS.forEach(c -> registry.counter(c, "outcome", "probe").increment());
        return registry;
    }

    @Test
    @DisplayName("알림 파일의 assist_* 이름이 전부 실제 노출 이름에 있다")
    void alertsReferenceRealMetricNames() throws IOException {
        String scraped = exposeAll().scrape();
        // "# TYPE <이름> counter" 줄에서 뽑는다. 지표 줄은 이름 뒤에 바로 { 가 붙어
        // 공백 기준 정규식이 안 맞는다 — 처음에 그렇게 썼다가 빈 집합을 얻었다.
        Set<String> exposed = Pattern.compile("^# TYPE (\\S+) ", Pattern.MULTILINE)
                .matcher(scraped).results()
                .map(m -> m.group(1))
                .collect(Collectors.toCollection(TreeSet::new));

        String rules = Files.readString(RULES, StandardCharsets.UTF_8);
        Matcher m = Pattern.compile("\\b(assist_[a-z0-9_]+)\\b").matcher(rules);
        Set<String> wanted = new TreeSet<>();
        while (m.find()) {
            wanted.add(m.group(1));
        }

        assertThat(wanted).as("알림이 assist_ 지표를 하나도 안 보면 이 테스트가 무의미하다").isNotEmpty();
        assertThat(exposed)
                .as("알림이 찾는 이름이 실제 노출 이름에 없다. 이 알림은 영원히 안 울린다")
                .containsAll(wanted);
    }
}
