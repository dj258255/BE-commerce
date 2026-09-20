package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>홀드아웃</b> 평가 — 프롬프트를 고칠 때 <b>보지 않은</b> 표본에서 다시 잰다.
 *
 * <p><b>왜 필요한가</b>: 지금 문서에 적힌 91~100%는 엔진이 만든 272건에서 유형별로 뽑아 재고,
 * 그 표본을 보고 판정 기준을 고친 뒤 <b>같은 표본에서 다시 잰 값</b>이다. 기준을 그 표본에
 * 맞춰 고쳤으니 그 표본에서 잘 나오는 것은 당연하다. 개발/홀드아웃을 나누지 않은 수치라
 * "쓸 만하다"의 근거로 쓰기에는 약하다.
 *
 * <p><b>무엇을 바꿨나</b>: 프롬프트({@link ResidualPromptBuilder})는 <b>한 글자도 건드리지 않고</b>,
 * 케이스만 새로 만든다. 시드를 고정해 재현은 되지만, 이 시드로 만든 건들은 기준을 고칠 때
 * 쓰이지 않았다.
 *
 * <p><b>케이스가 실제와 같은 모양인가</b>: 사실 문장을 지어내지 않고
 * {@code DomainContributors}가 대사 결과를 옮길 때 쓰는 문장 형식
 * ({@code "대사 %s (거래일 %s) — 내부 %s / 외부 %s"})과 {@link FactPack}의 줄 형식
 * ({@code "날짜 · 출처 · 요약"})을 그대로 쓴다. 값만 새로 뽑는다.
 *
 * <p><b>정답</b>: 켜 둔 유형은 {@code INTERNAL_RECORD_LOST} 하나뿐이고, 그 판정 기준은
 * "내부 기록 금액이 아예 없을 것"이다. 그래서 {@code EXTERNAL_ONLY}(내부 없음 / 외부 있음)만
 * 정답이 그 값이고, <b>나머지는 전부 기권이 정답</b>이다.
 *
 * <p>Ollama 와 세 모델이 있어야 돈다. {@code ./gradlew evalTest} 로만 실행된다.
 */
@Tag("eval")
@DisplayName("잔여 후보 — 홀드아웃 정확도")
class ResidualHoldoutEvalTest {

    /** 기준을 고칠 때 쓰지 않은 시드. 이 값을 바꾸면 다른 표본이 된다. */
    private static final long HOLDOUT_SEED = 20260905L;

    private static final int PER_FAMILY =
            Integer.getInteger("eval.perFamily", 15);

    private static final List<String> MODELS =
            List.of("qwen3:14b", "qwen3:8b", "llama3.1:8b");

    /** 한 건의 홀드아웃 케이스 — 사실 묶음과 정답. */
    private record Case(String family, FactPack facts, ResolveCause expected) {
        /** 정답이 기권인가. */
        boolean expectsAbstain() {
            return expected == null;
        }
    }

    // ────────────────────────────── 케이스 생성 ──────────────────────────────

    /** {@code FactPack} 의 한 줄 형식. */
    private static String line(LocalDate d, String source, String summary) {
        return "%s · %s · %s".formatted(d, source, summary);
    }

    /** {@code DomainContributors} 가 대사 결과를 옮길 때 쓰는 문장. */
    private static String recon(String result, LocalDate tradeDate, Long internal, Long external) {
        return "대사 %s (거래일 %s) — 내부 %s / 외부 %s".formatted(
                result, tradeDate,
                internal == null ? "없음" : "%,d".formatted(internal),
                external == null ? "없음" : "%,d".formatted(external));
    }

    /** {@code internalRecordAbsent} 는 대사 결과가 <외부에만 있음>일 때만 참이다. */
    private static FactPack pack(String orderNo, List<String> facts, Set<Long> amounts,
                                 LocalDate d, boolean internalRecordAbsent) {
        return new FactPack(orderNo, facts, amounts, Set.of(d), null, true, internalRecordAbsent);
    }

    private List<Case> buildHoldout() {
        Random rnd = new Random(HOLDOUT_SEED);
        List<Case> cases = new ArrayList<>();

        for (int i = 0; i < PER_FAMILY; i++) {
            LocalDate d = LocalDate.of(2026, 8, 1).plusDays(rnd.nextInt(28));
            long amount = (rnd.nextInt(400) + 10) * 1_000L;
            String ord = "ORD-H%05d".formatted(rnd.nextInt(90000) + 10000);

            // ① 정답 = INTERNAL_RECORD_LOST — 외부에만 있고 내부 금액이 아예 없다
            cases.add(new Case("EXTERNAL_ONLY", pack(ord,
                    List.of(line(d, "RECONCILIATION", recon("EXTERNAL_ONLY", d, null, amount))),
                    Set.of(amount), d, true), ResolveCause.INTERNAL_RECORD_LOST));

            // ② 정답 = 기권 — 양쪽에 있고 금액만 다르다(수수료·부분취소 등이 섞인 자리)
            long internal = amount;
            long external = amount - (rnd.nextInt(20) + 1) * 100L;
            cases.add(new Case("AMOUNT_MISMATCH", pack(ord + "-A",
                    List.of(line(d, "PAYMENT", "결제 승인 %,d원".formatted(internal)),
                            line(d, "RECONCILIATION", recon("AMOUNT_MISMATCH", d, internal, external))),
                    Set.of(internal, external), d, false), null));

            // ③ 정답 = 기권 — 내부에만 있다(방향이 반대라 위 유형이 아니다)
            cases.add(new Case("INTERNAL_ONLY", pack(ord + "-B",
                    List.of(line(d, "PAYMENT", "결제 승인 %,d원".formatted(amount)),
                            line(d, "RECONCILIATION", recon("INTERNAL_ONLY", d, amount, null))),
                    Set.of(amount), d, false), null));
        }
        return cases;
    }

    // ────────────────────────────── 실행 ──────────────────────────────

    @Test
    @DisplayName("프롬프트를 고정한 채 새 표본에서 세 모델을 다시 잰다")
    void measureHoldout() {
        List<Case> cases = buildHoldout();
        ResidualPromptBuilder prompts = new ResidualPromptBuilder();

        System.out.println("\n╔══ 잔여 후보 홀드아웃 (seed=" + HOLDOUT_SEED
                + ", 케이스 " + cases.size() + "건) ══");

        Map<String, Map<String, int[]>> table = new LinkedHashMap<>();   // model → family → [원시맞음, 전체, 가드후맞음, 전체]

        for (String model : MODELS) {
            OllamaResidualAdapter adapter =
                    new OllamaResidualAdapter(prompts, "http://localhost:11434", model, 120);
            Map<String, int[]> byFamily = new LinkedHashMap<>();

            for (Case c : cases) {
                Optional<ResidualSuggestion> out;
                try {
                    out = adapter.suggest(c.facts());
                } catch (RuntimeException e) {
                    // 모델이 죽거나 타임아웃이면 기권으로 센다 — 실제 서비스도 그렇게 흡수한다
                    out = Optional.empty();
                }
                boolean correct = c.expectsAbstain()
                        ? out.isEmpty()
                        : out.map(s -> s.cause() == c.expected()).orElse(false);

                // 가드 8 을 그대로 적용한 결과 — 시스템이 실제로 운영자에게 내는 값이다.
                Optional<ResidualSuggestion> guarded = out.filter(
                        s -> s.cause() != ResolveCause.INTERNAL_RECORD_LOST
                                || c.facts().internalRecordAbsent());
                boolean correctGuarded = c.expectsAbstain()
                        ? guarded.isEmpty()
                        : guarded.map(s -> s.cause() == c.expected()).orElse(false);

                int[] tally = byFamily.computeIfAbsent(c.family(), k -> new int[4]);
                tally[1]++;
                tally[3]++;
                if (correct) {
                    tally[0]++;
                }
                if (correctGuarded) {
                    tally[2]++;
                }
            }
            table.put(model, byFamily);
        }

        System.out.println("\n(모델 원시 → 가드 8 적용 후)\n\n| 유형 | 정답 | " + String.join(" | ", MODELS) + " |");
        System.out.println("|---|---|" + "---|".repeat(MODELS.size()));
        for (String family : List.of("EXTERNAL_ONLY", "AMOUNT_MISMATCH", "INTERNAL_ONLY")) {
            StringBuilder row = new StringBuilder("| `" + family + "` | "
                    + ("EXTERNAL_ONLY".equals(family) ? "INTERNAL_RECORD_LOST" : "기권") + " |");
            for (String model : MODELS) {
                int[] t = table.get(model).getOrDefault(family, new int[4]);
                row.append(" %d/%d → **%d/%d** |".formatted(t[0], t[1], t[2], t[3]));
            }
            System.out.println(row);
        }

        StringBuilder total = new StringBuilder("| **전체** | | ");
        for (String model : MODELS) {
            int ok = table.get(model).values().stream().mapToInt(t -> t[0]).sum();
            int all = table.get(model).values().stream().mapToInt(t -> t[1]).sum();
            int okG = table.get(model).values().stream().mapToInt(t -> t[2]).sum();
            total.append("%.0f%% → **%.0f%%** | ".formatted(
                    100.0 * ok / Math.max(1, all), 100.0 * okG / Math.max(1, all)));
        }
        System.out.println(total);
        System.out.println("╚════════════════════════════════════════\n");

        // 수치를 고정하지 않는다 — 이 테스트는 <재는> 것이지 통과시키는 것이 아니다.
        assertThat(cases).isNotEmpty();
        assertThat(table).hasSize(MODELS.size());
    }
}
