package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>규칙(템플릿) 대비 개선이 있는가</b> — 잔여 후보를 끄면서 배운 기준을 여기에 먼저 적용한다.
 *
 * <p>절대 품질이 아니라 <b>기존 방식 대비</b>로 잰다. 여기서 기존은 모델 없는
 * {@link TemplateNarrativeAdapter} 다. 그것보다 낫지 않으면 붙일 이유가 없다.
 *
 * <p><b>무엇으로 재나</b>: 서술은 정확도로 못 잰다. 그래서 셋을 본다.
 * <ol>
 *   <li><b>출처 없는 숫자</b> — 코드가 셀 수 있다. 이게 있으면 그 문단은 못 쓴다</li>
 *   <li><b>길이</b> — 템플릿은 사실을 그대로 이어 붙여 길다. 짧아지는 것이 서술의 값이다</li>
 *   <li><b>기권</b> — 못 만들겠다고 말하는가</li>
 * </ol>
 * "읽기 좋은가"는 이 셋으로 안 잡힌다. 그건 사람이 읽어야 하고, 그 자리는 남는다.
 *
 * <p>Ollama 가 필요하다. {@code ./gradlew evalTest} 로만 실행된다.
 */
@Tag("eval")
@DisplayName("타임라인 서술 — 템플릿 대비 개선이 있는가")
class NarrativeLiftEvalTest {

    private final NumericProvenanceGuard guard = new NumericProvenanceGuard();

    /** 실제 타임라인이 내는 문장 형식 그대로. 값만 바꾼다. */
    private static FactPack caseOf(int i) {
        long amount = (i + 1) * 10_000L;
        long external = amount - (i + 1) * 300L;
        LocalDate d = LocalDate.of(2026, 8, 1).plusDays(i);
        return new FactPack("ORD-N%03d".formatted(i),
                List.of("%s · ORDER · 주문 생성 %,d원".formatted(d, amount),
                        "%s · PAYMENT · 결제 READY → IN_PROGRESS (USER, TOSS_PAYMENTS)".formatted(d),
                        "%s · PAYMENT · 결제 IN_PROGRESS → DONE (PG, TOSS_PAYMENTS)".formatted(d),
                        "%s · LEDGER · 원장 기록 %,d원".formatted(d, amount),
                        "%s · RECONCILIATION · 대사 AMOUNT_MISMATCH (거래일 %s) — 내부 %,d / 외부 %,d"
                                .formatted(d.plusDays(1), d.plusDays(1), amount, external)),
                Set.of(amount, external), Set.of(d, d.plusDays(1)), null, true);
    }

    private record Score(int n, int unsourced, int abstained, double avgChars) {
        String line(String label) {
            return "  %-22s 출처없는숫자 %d건 · 기권 %d건 · 평균 %.0f자"
                    .formatted(label, unsourced, abstained, avgChars);
        }
    }

    private Score run(TimelineNarrativePort port, List<FactPack> cases) {
        int unsourced = 0, abstained = 0;
        List<Integer> lens = new ArrayList<>();
        for (FactPack f : cases) {
            Optional<String> out = port.narrate(f);
            if (out.isEmpty()) {
                abstained++;
                continue;
            }
            if (!guard.verify(out.get(), f).isEmpty()) {
                unsourced++;
            }
            lens.add(out.get().length());
        }
        double avg = lens.isEmpty() ? 0 : lens.stream().mapToInt(Integer::intValue).average().orElse(0);
        return new Score(cases.size(), unsourced, abstained, avg);
    }

    @Test
    @DisplayName("템플릿과 모델을 같은 사실에 태워 나란히 잰다")
    void compareAgainstTemplateBaseline() {
        List<FactPack> cases = new ArrayList<>();
        for (int i = 0; i < Integer.getInteger("eval.narrativeCases", 10); i++) {
            cases.add(caseOf(i));
        }

        Score baseline = run(new TemplateNarrativeAdapter(), cases);
        Score model = run(new OllamaNarrativeAdapter(
                "http://localhost:11434", "qwen3:8b", 120), cases);

        System.out.printf("%n╔══ 타임라인 서술 — 템플릿 대비 (%d건) ══%n", cases.size());
        System.out.println(baseline.line("템플릿(기존)"));
        System.out.println(model.line("qwen3:8b"));
        System.out.println("╚════════════════════════════════════════");

        // 수치를 통과 조건으로 걸지 않는다 — 재는 테스트다.
        assertThat(baseline.n()).isEqualTo(cases.size());
    }
}
