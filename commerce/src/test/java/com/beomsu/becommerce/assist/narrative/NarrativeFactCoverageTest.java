package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.AmountCoverageGuard;
import com.beomsu.becommerce.assist.draft.FactPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 서술이 <b>사실 묶음의 금액을 다 담았는지</b>를 기계로 센다. 선호가 아니라 포함 여부다.
 *
 * <p><b>왜 선호 대신 이걸 재나.</b> 쌍 비교로 "어느 쪽이 나은가"를 물으면 판정하는 쪽의 편향이
 * 들어간다. 심판 모델은 순서에 흔들렸고, 강한 심판을 세워 봐도 <b>템플릿이 "그다음" 사슬이라
 * 한눈에 구별돼 블라인드가 성립하지 않는다.</b> 그 상태의 선호는 자기선호 편향과 못 가른다.
 *
 * <p>그런데 30건을 읽다 보니 편향과 무관하게 <b>기계로 확인되는 차이</b>가 있었다 — 모델이
 * 금액을 빠뜨린 건이 있었다. 대사 불일치를 조사하는 사람에게 금액 없는 요약은 쓸모가 없다.
 * 이건 취향이 아니라 <b>결손</b>이고, 세면 된다.
 */
@Tag("eval")
@DisplayName("서술 — 금액을 다 담았는가(선호가 아니라 결손)")
class NarrativeFactCoverageTest {

    private record Miss(String orderNo, String port, List<Long> missing) {}

    /** 실제로 운영에서 도는 그 가드를 쓴다 — 실험용으로 따로 만들면 둘이 갈린다. */
    private final AmountCoverageGuard guard = new AmountCoverageGuard();

    @Test
    @DisplayName("사실 묶음의 금액이 서술에 남아 있는지 센다")
    void countMissingAmounts() {
        var ports = List.<TimelineNarrativePort>of(
                new TemplateNarrativeAdapter(),
                new OllamaNarrativeAdapter("http://localhost:11434", "qwen3:8b", 120));

        System.out.println("\n╔══ 서술 금액 결손 ══");
        for (TimelineNarrativePort port : ports) {
            List<Miss> misses = new ArrayList<>();
            int total = 0;
            for (int i = 0; i < Integer.getInteger("eval.coverCases", 30); i++) {
                FactPack facts = NarrativeBlindDumpTest.caseOf(i);
                Optional<String> out = port.narrate(facts);
                if (out.isEmpty()) {
                    continue;
                }
                total++;
                List<Long> missing = guard.missing(out.get(), facts);
                if (!missing.isEmpty()) {
                    misses.add(new Miss(facts.orderNo(), port.name(), missing));
                }
            }
            System.out.printf("  %-24s 결손 %d / %d건%n", port.name(), misses.size(), total);
            misses.forEach(m -> System.out.printf("      %s 빠진 금액 %s%n", m.orderNo(), m.missing()));
        }
        System.out.println("╚═══════════════════════════════");
    }
}
