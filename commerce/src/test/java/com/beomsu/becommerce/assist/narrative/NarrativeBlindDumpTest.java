package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.FactPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 쌍 비교 표본을 <b>출처를 가린 파일로</b> 뽑는다. 사람이든 다른 심판이든, 무엇이 템플릿이고
 * 무엇이 모델인지 모르는 채로 읽고 고르게 하기 위해서다.
 *
 * <p><b>왜 파일로 뽑나.</b> 표본이 0건이던 진짜 이유는 판단이 어려워서가 아니라 API 를 손으로
 * 여러 번 호출해야 해서였다. 화면(`narrative-compare.html`)은 운영자용이고, 이 파일은
 * <b>실험용</b>이다 — 앱과 DB 없이 ollama 만으로 돌아간다.
 *
 * <p><b>케이스 모양을 갈랐다.</b> 처음엔 금액만 다른 같은 모양(AMOUNT_MISMATCH)을 반복해서
 * 뽑고 있었다. 같은 시나리오를 30번 읽으면 서술의 차이가 드러날 자리가 없다. 대사 결과 네 종류와
 * 사실 개수를 섞는다.
 *
 * <p><b>A/B 배정을 무작위로 하고 키를 따로 쓴다.</b> 배정이 무작위여야 고른 결과에서
 * <b>출처 선호</b>와 <b>위치 선호</b>를 갈라 잴 수 있다. 위치 편향이 없으면 A 를 고른 비율이
 * 절반 근처여야 한다.
 */
@Tag("eval")
@DisplayName("서술 쌍 비교 — 출처를 가린 표본 뽑기")
class NarrativeBlindDumpTest {

    private static final Path OUT = Path.of("build/narrative-blind");

    /** 대사 결과마다 사실 묶음의 모양이 다르다. 서술이 어려워지는 지점도 다르다. */
    static FactPack caseOf(int i) {
        long amount = 10_000L + (i * 7_300L) % 240_000L;
        LocalDate d = LocalDate.of(2026, 6, 1).plusDays(i * 3L);
        String pg = (i % 3 == 0) ? "TOSS_PAYMENTS" : (i % 3 == 1) ? "KAKAOPAY" : "MOCK_PG";
        List<String> f = new ArrayList<>();
        f.add("%s · ORDER · 주문 생성 %,d원".formatted(d, amount));
        f.add("%s · PAYMENT · 결제 READY → IN_PROGRESS (USER, %s)".formatted(d, pg));

        switch (i % 4) {
            case 0 -> {   // 금액 불일치 — 수수료 차이인지 진짜 오차인지 사람이 갈라야 한다
                long ext = amount - 300L * (1 + i % 7);
                f.add("%s · PAYMENT · 결제 IN_PROGRESS → DONE (PG, %s)".formatted(d, pg));
                f.add("%s · LEDGER · 원장 기록 %,d원".formatted(d, amount));
                f.add("%s · RECONCILIATION · 대사 AMOUNT_MISMATCH — 내부 %,d / 외부 %,d"
                        .formatted(d.plusDays(1), amount, ext));
                return new FactPack("ORD-B%03d".formatted(i), f, Set.of(amount, ext),
                        Set.of(d, d.plusDays(1)), null, true, false);
            }
            case 1 -> {   // 외부에만 있음 — 가장 위험한 유형
                f.add("%s · PAYMENT · 결제 IN_PROGRESS → UNKNOWN (PG 무응답, %s)".formatted(d, pg));
                f.add("%s · RECONCILIATION · 대사 RECON_EXTERNAL_ONLY — 외부 %,d, 내부 기록 없음"
                        .formatted(d.plusDays(1), amount));
                return new FactPack("ORD-B%03d".formatted(i), f, Set.of(amount),
                        Set.of(d, d.plusDays(1)), null, true, true);
            }
            case 2 -> {   // 부분취소 미반영 — 사건이 여럿이라 순서가 중요하다
                long refund = amount / 3;
                f.add("%s · PAYMENT · 결제 IN_PROGRESS → DONE (PG, %s)".formatted(d, pg));
                f.add("%s · LEDGER · 원장 기록 %,d원".formatted(d, amount));
                f.add("%s · PAYMENT · 부분취소 %,d원 (USER)".formatted(d.plusDays(2), refund));
                f.add("%s · LEDGER · 환불 기록 %,d원".formatted(d.plusDays(2), refund));
                f.add("%s · RECONCILIATION · 대사 AMOUNT_MISMATCH — 내부 %,d / 외부 %,d"
                        .formatted(d.plusDays(3), amount - refund, amount));
                return new FactPack("ORD-B%03d".formatted(i), f,
                        Set.of(amount, refund, amount - refund),
                        Set.of(d, d.plusDays(2), d.plusDays(3)), null, true, false);
            }
            default -> { // 이의제기까지 붙은 긴 사건 — 사실이 많을 때 요약이 되는가
                f.add("%s · PAYMENT · 결제 IN_PROGRESS → DONE (PG, %s)".formatted(d, pg));
                f.add("%s · LEDGER · 원장 기록 %,d원".formatted(d, amount));
                f.add("%s · SETTLEMENT · 정산 집계 %,d원".formatted(d.plusDays(1), amount));
                f.add("%s · DISPUTE · 이의제기 접수 (사유: 미수취)".formatted(d.plusDays(5)));
                f.add("%s · DISPUTE · 이의제기 패소 — 대금 회수 %,d원".formatted(d.plusDays(9), amount));
                f.add("%s · RECONCILIATION · 대사 AMOUNT_MISMATCH — 내부 %,d / 외부 0"
                        .formatted(d.plusDays(10), amount));
                return new FactPack("ORD-B%03d".formatted(i), f, Set.of(amount),
                        Set.of(d, d.plusDays(1), d.plusDays(5), d.plusDays(9)), null, true, false);
            }
        }
    }

    @Test
    @DisplayName("A/B 를 무작위로 배정해 가린 본문과 키를 따로 쓴다")
    void dump() throws Exception {
        int n = Integer.getInteger("eval.blindCases", 30);
        long seed = Long.getLong("eval.blindSeed", 20260905L);
        var template = new TemplateNarrativeAdapter();
        var model = new OllamaNarrativeAdapter("http://localhost:11434", "qwen3:8b", 120);
        Random rnd = new Random(seed);

        Files.createDirectories(OUT);
        StringBuilder blind = new StringBuilder("# 서술 쌍 비교 — 출처를 가린 표본\n")
                .append("# 각 건에서 A 와 B 중 <운영자가 상황을 파악하기에> 나은 쪽을 고른다.\n")
                .append("# 길다고 좋은 것이 아니고 짧다고 좋은 것도 아니다. 차이를 못 느끼면 TIE.\n\n");
        StringBuilder key = new StringBuilder("case,sourceA,sourceB\n");

        int made = 0;
        for (int i = 0; i < n * 2 && made < n; i++) {
            FactPack facts = caseOf(i);
            Optional<String> t = template.narrate(facts);
            Optional<String> m = model.narrate(facts);
            if (t.isEmpty() || m.isEmpty()) {
                continue;
            }
            boolean flip = rnd.nextBoolean();
            String textA = flip ? m.get() : t.get();
            String textB = flip ? t.get() : m.get();
            String id = "C%02d".formatted(made + 1);

            blind.append("=== ").append(id).append(" ===\n")
                 .append("[A]\n").append(textA.strip()).append("\n\n")
                 .append("[B]\n").append(textB.strip()).append("\n\n");
            key.append(id).append(',').append(flip ? "model" : "template")
               .append(',').append(flip ? "template" : "model").append('\n');
            made++;
        }

        Files.writeString(OUT.resolve("blind.txt"), blind, StandardCharsets.UTF_8);
        Files.writeString(OUT.resolve("key.csv"), key, StandardCharsets.UTF_8);
        System.out.println("표본 " + made + "건 → " + OUT.toAbsolutePath());
        System.out.println("  blind.txt — 고르는 사람이 읽는다(출처 없음)");
        System.out.println("  key.csv   — 다 고른 뒤에 연다");
    }
}
