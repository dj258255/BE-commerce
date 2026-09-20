package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.FactPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 사람이 고른 표본이 0건이라 서술을 켜지 못하고 있다. 그 <b>1차 신호</b>를 심판 모델로 만든다.
 *
 * <p><b>심판을 다른 모델로 쓴다.</b> 자기선호 편향은 잘 알려져 있다 — 심판과 생성자가 같은
 * 모델(또는 같은 계열)이면 자기 출력을 후하게 준다. 그래서 서술은 {@code qwen3:8b} 가 쓰고
 * 심판은 <b>{@code llama3.1:8b}</b> 가 본다. 계열이 다르다.
 *
 * <p><b>순서도 뒤집어 두 번 묻는다.</b> 위치 편향이 알려져 있어서, A/B 를 바꿔 두 번 물어
 * <b>답이 뒤집히면 동점</b>으로 센다. 순서를 바꿨다고 답이 바뀌면 그건 선호가 아니다.
 *
 * <p><b>이건 사람을 대체하지 않는다.</b> 심판 모델은 사람과 다르게 판단하고, 특히
 * <b>긴 답을 선호하는 편향</b>이 알려져 있다. 여기서 만드는 것은 사람이 볼 <b>1차 신호</b>이고,
 * 기본값을 바꾸는 근거로는 <b>사람이 고른 표본</b>이 여전히 필요하다.
 */
@Tag("eval")
@DisplayName("서술 — 다른 계열 모델을 심판으로 세운 1차 신호")
class NarrativeJudgeEvalTest {

    private static final String JUDGE = "llama3.1:8b";

    private final RestClient client = RestClient.builder()
            .baseUrl("http://localhost:11434")
            .requestFactory(new SimpleClientHttpRequestFactory() {{
                setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                setReadTimeout((int) Duration.ofSeconds(180).toMillis());
            }}).build();

    private static FactPack caseOf(int i) {
        long amount = (i + 1) * 10_000L;
        long external = amount - (i + 1) * 300L;
        LocalDate d = LocalDate.of(2026, 8, 1).plusDays(i);
        return new FactPack("ORD-J%03d".formatted(i),
                List.of("%s · ORDER · 주문 생성 %,d원".formatted(d, amount),
                        "%s · PAYMENT · 결제 READY → IN_PROGRESS (USER, TOSS_PAYMENTS)".formatted(d),
                        "%s · PAYMENT · 결제 IN_PROGRESS → DONE (PG, TOSS_PAYMENTS)".formatted(d),
                        "%s · LEDGER · 원장 기록 %,d원".formatted(d, amount),
                        "%s · RECONCILIATION · 대사 AMOUNT_MISMATCH (거래일 %s) — 내부 %,d / 외부 %,d"
                                .formatted(d.plusDays(1), d.plusDays(1), amount, external)),
                Set.of(amount, external), Set.of(d, d.plusDays(1)), null, true);
    }

    /** 심판에게는 <b>어느 쪽이 무엇인지 말하지 않는다.</b> */
    private String ask(String a, String b) {
        String system = """
                당신은 결제 운영자입니다. 같은 주문에 대한 두 요약 A 와 B 를 읽고,
                <운영자가 상황을 파악하기에> 어느 쪽이 나은지 하나만 고릅니다.

                기준은 <읽고 무슨 일이 있었는지 알겠는가> 입니다.
                길다고 좋은 것이 아니고, 짧다고 좋은 것도 아닙니다.
                차이를 못 느끼면 TIE 입니다.

                답은 한 줄입니다: A 또는 B 또는 TIE
                """;
        String user = "[A]\n" + a + "\n\n[B]\n" + b + "\n";
        try {
            Map<?, ?> res = client.post().uri("/api/chat")
                    .body(Map.of("model", JUDGE, "stream", false, "think", false,
                            "messages", List.of(Map.of("role", "system", "content", system),
                                    Map.of("role", "user", "content", user))))
                    .retrieve().body(Map.class);
            Object m = res == null ? null : res.get("message");
            String t = m instanceof Map<?, ?> mm ? String.valueOf(mm.get("content")).strip() : "";
            if (t.startsWith("A")) return "A";
            if (t.startsWith("B")) return "B";
            return "TIE";
        } catch (RuntimeException e) {
            return "TIE";
        }
    }

    @Test
    @DisplayName("순서를 뒤집어 두 번 묻고, 뒤집히면 동점으로 센다")
    void judgeWithOrderReversal() {
        var template = new TemplateNarrativeAdapter();
        var model = new OllamaNarrativeAdapter("http://localhost:11434", "qwen3:8b", 120);

        int templateWin = 0, modelWin = 0, tie = 0, flipped = 0;
        List<String> lines = new ArrayList<>();

        for (int i = 0; i < Integer.getInteger("eval.judgeCases", 8); i++) {
            FactPack facts = caseOf(i);
            Optional<String> t = template.narrate(facts);
            Optional<String> m = model.narrate(facts);
            if (t.isEmpty() || m.isEmpty()) {
                continue;
            }
            // 순서를 바꿔 두 번 묻는다.
            String first = ask(t.get(), m.get());     // A=템플릿
            String second = ask(m.get(), t.get());    // A=모델
            String firstPick = "A".equals(first) ? "template" : "B".equals(first) ? "model" : "tie";
            String secondPick = "A".equals(second) ? "model" : "B".equals(second) ? "template" : "tie";

            String verdict;
            if (firstPick.equals(secondPick)) {
                verdict = firstPick;
            } else {
                verdict = "tie";                       // 순서를 바꾸니 답이 뒤집혔다 = 선호가 아니다
                flipped++;
            }
            switch (verdict) {
                case "template" -> templateWin++;
                case "model" -> modelWin++;
                default -> tie++;
            }
            lines.add("      %s → %s".formatted(facts.orderNo(), verdict));
        }

        System.out.printf("%n╔══ 서술 쌍 비교 — 심판 %s (자기선호 회피) ══%n", JUDGE);
        lines.forEach(System.out::println);
        System.out.printf("  템플릿 %d · 모델 %d · 동점 %d  (그중 순서 뒤집힘 %d)%n",
                templateWin, modelWin, tie, flipped);
        System.out.println("  ※ 사람 표본을 대체하지 않는다. 1차 신호다");
        System.out.println("╚═══════════════════════════════════════════");

        assertThat(templateWin + modelWin + tie).isPositive();
    }
}
