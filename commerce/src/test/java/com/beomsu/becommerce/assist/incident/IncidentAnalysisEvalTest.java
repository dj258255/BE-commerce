package com.beomsu.becommerce.assist.incident;

import com.beomsu.becommerce.assist.EvalBaseline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 장애 로그로 원인을 고르는 일에서 <b>규칙 대비 모델이 나은가</b>를 잰다.
 *
 * <p><b>로그가 진짜다.</b> 지어낸 문장이 아니라 이 프로젝트에서 <b>실제로 찍힌</b> 것이다.
 * <ul>
 *   <li>{@code DB_TIMEOUT} — Toxiproxy 로 MySQL 을 끊고 돌린 카오스 실행에서 나온 것</li>
 *   <li>{@code TIME_SKEW} — 시계가 1시간 틀어진 웹훅을 실제로 넣어 401 을 받은 것</li>
 *   <li>{@code QUEUE_BACKLOG} — 보류 웹훅이 재시도를 소진하며 쌓인 것</li>
 * </ul>
 *
 * <p><b>정답은 우리가 안다.</b> 무엇을 주입했는지 알고 넣었으므로 채점이 프록시가 아니다.
 * 서술(18 문서)에서 "읽기 좋은가"를 못 재 사람 손을 빌려야 했던 것과 다른 점이다.
 *
 * <p><b>기준선은 규칙이다.</b> 절대 정확도가 아니라 <b>규칙 대비</b>로 본다 — 잔여 후보에서
 * 그걸 안 보고 켰다가 껐다.
 */
@Tag("eval")
@DisplayName("장애 로그 원인 분석 — 규칙 대비")
class IncidentAnalysisEvalTest {

    private static final Path DIR = Path.of("src/test/resources/incident-logs");

    /** 한 표본. 파일 이름이 곧 정답이다 — 실제로 그 장애를 넣고 받은 로그이기 때문이다. */
    private record Sample(IncidentCause cause, String file, String log) {}

    /**
     * 표본을 읽는다. 파일 이름은 {@code <원인>.log} 또는 {@code <원인>-<번호>.log} 다.
     *
     * <p><b>원인 하나에 로그 하나로 묶어 두지 않는다.</b> 처음엔 {@code Map<IncidentCause, String>}
     * 이라 표본이 원인 수(4건)에 갇혔고, 그 얇은 표본에서 한 건이 결론을 뒤집었다. 같은 원인도
     * 드러나는 모습이 여러 가지다 — DB 가 느린 것과 커넥션이 고갈된 것은 같은 원인인데 로그가 다르다.
     */
    private static List<Sample> realLogs() {
        List<Sample> out = new ArrayList<>();
        try (var files = Files.list(DIR)) {
            files.filter(f -> f.getFileName().toString().endsWith(".log")).sorted().forEach(p -> {
                String file = p.getFileName().toString();
                String name = file.replace(".log", "");
                int dash = name.lastIndexOf('-');
                if (dash > 0) {
                    name = name.substring(0, dash);
                }
                try {
                    out.add(new Sample(IncidentCause.valueOf(name), file,
                            Files.readString(p, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    private record Score(String name, int correct, int abstained, int wrong) {
        String line() {
            return "  %-14s 맞음 %d · 기권 %d · 틀림 %d".formatted(name, correct, abstained, wrong);
        }
    }

    private final EvidenceGroundingGuard grounding = new EvidenceGroundingGuard();

    /**
     * 표본이 바뀌었는지 한 줄로 나타낸다. 회귀가 났을 때 <b>모델이 나빠진 것과 표본이 바뀐
     * 것을 구별</b>해야 하는데, 파일 이름만 봐서는 안에 든 내용이 바뀐 것을 모른다.
     */
    private static String fingerprint(List<Sample> cases) {
        String joined = cases.stream().map(c -> c.file() + ":" + c.log().length())
                .sorted().reduce("", (a, b) -> a + "|" + b);
        return Integer.toHexString(joined.hashCode());
    }

    private Score run(IncidentAnalysisPort port, List<Sample> cases, boolean guard) {
        int correct = 0, abstained = 0, wrong = 0;
        List<String> detail = new ArrayList<>();
        for (Sample c : cases) {
            Optional<IncidentDiagnosis> out = port.diagnose(c.log());
            // 인용이 원문에 없으면 버린다 — 근거 없는 판단은 기권과 같게 취급한다.
            if (guard && out.isPresent() && !grounding.grounded(out.get(), c.log())) {
                out = Optional.empty();
            }
            String got;
            if (out.isEmpty() || out.get().cause() == IncidentCause.UNKNOWN) {
                abstained++;
                got = "기권";
            } else if (out.get().cause() == c.cause()) {
                correct++;
                got = "맞음";
            } else {
                wrong++;
                got = "틀림(" + out.get().cause() + ")";
            }
            detail.add("      %-22s → %s".formatted(c.file(), got));
        }
        detail.forEach(System.out::println);
        return new Score(port.name(), correct, abstained, wrong);
    }

    @Test
    @DisplayName("실제로 찍힌 로그에 규칙과 모델을 나란히 태운다")
    void compareAgainstRuleBaseline() {
        List<Sample> cases = realLogs();
        assertThat(cases).as("실제 로그 표본이 있어야 한다").isNotEmpty();

        System.out.printf("%n╔══ 장애 로그 원인 분석 (실제 로그 %d건) ══%n", cases.size());
        System.out.println("  [규칙 기준선]");
        Score rule = run(new RuleBasedIncidentAnalyzer(), cases, false);
        var ollama = new OllamaIncidentAnalyzer("http://localhost:11434", "qwen3:8b", 120);
        System.out.println("  [모델 — 가드 없음]");
        Score raw = run(ollama, cases, false);
        System.out.println("  [모델 — 근거 대조 가드]");
        Score guarded = run(ollama, cases, true);
        // 실제로 켤 후보: 규칙이 먼저 답하고 기권한 자리에만 모델을 부른다.
        System.out.println("  [규칙 우선 + 기권 자리만 모델]");
        Score first = run(new RuleFirstIncidentAnalyzer(new RuleBasedIncidentAnalyzer(), ollama,
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()), cases, false);

        System.out.println();
        System.out.println(rule.line());
        System.out.println(raw.line());
        System.out.println(guarded.line().replace(guarded.name(), guarded.name() + "+가드"));
        System.out.println(first.line());
        System.out.println("╚═══════════════════════════════════════");

        // <b>절대 수치를 통과 조건으로 걸지 않는다.</b> 모델 정확도에 임계를 걸면 표본이 바뀔
        // 때마다 테스트가 깨진다. 대신 <b>회귀</b>를 본다 — 지난번보다 나빠졌으면 표본 탓이
        // 아니라 무언가 바뀐 것이다. 켤 때 잰 값이 시간이 지나 사실이 아니게 되는 것이
        // LLM 을 운영에 올린 뒤 가장 잡기 어려운 문제로 꼽힌다.
        String regressed = EvalBaseline.compare("incident-analysis", Map.of(
                "rule.correct", (double) rule.correct(),
                "model.correct", (double) raw.correct(),
                "ruleFirst.correct", (double) first.correct(),
                "ruleFirst.wrong", (double) -first.wrong()),   // 틀림은 적을수록 좋으므로 부호를 뒤집는다
                Map.of("model", ollama.name(),
                       "samples", String.valueOf(cases.size()),
                       "corpusFingerprint", fingerprint(cases)));
        assertThat(regressed)
                .as("지난 기준선보다 나빠졌다. 모델·프롬프트·표본 중 무엇이 바뀌었는지 확인하고,"
                        + " 바뀐 것이 맞다면 EvalBaseline.accept 로 기준선을 갱신한다:%n%s", regressed)
                .isEmpty();

        assertThat(rule.name()).isEqualTo("rule");
    }
}
