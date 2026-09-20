package com.beomsu.becommerce.assist.evidence;

import com.beomsu.becommerce.assist.draft.AmountCoverageGuard;
import com.beomsu.becommerce.assist.draft.DraftService;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.narrative.TimelineNarrativePort;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("분쟁 증빙 조립 — 못 채운 항목을 드러내고, 요약이 실패해도 증빙은 낸다")
class DisputeEvidenceServiceTest {

    private static final FactPack FACTS = new FactPack("ORD-D1",
            List.of("2026-08-01 · ORDER · 주문 생성 50,000원",
                    "2026-08-01 · PAYMENT · 결제 IN_PROGRESS → DONE (PG, TOSS_PAYMENTS)",
                    "2026-08-01 · LEDGER · 원장 기록 50,000원",
                    "2026-08-05 · ESCROW · 구매확정 — 에스크로 RELEASED",
                    "2026-08-20 · DISPUTE · 이의제기 접수 (사유: 미수취)"),
            Set.of(50_000L), Set.of(LocalDate.of(2026, 8, 1)), null, true);

    private DraftService draftService;
    private TimelineNarrativePort narrator;
    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        draftService = mock(DraftService.class);
        narrator = mock(TimelineNarrativePort.class);
        registry = new SimpleMeterRegistry();
        when(draftService.factsFor(anyString(), any())).thenReturn(FACTS);
    }

    private DisputeEvidenceService service() {
        return new DisputeEvidenceService(draftService, new DisputeEvidenceAssembler(),
                new NumericProvenanceGuard(), new AmountCoverageGuard(),
                Optional.of(narrator), registry);
    }

    private double counted(String outcome) {
        var c = registry.find(DisputeEvidenceService.METRIC).tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("출처별로 항목을 가르고, 사실이 없는 항목은 빈 채로 gaps 에 올린다")
    void splitsBySourceAndReportsGaps() {
        when(narrator.narrate(any())).thenReturn(Optional.of(
                "주문 50,000원이 2026-08-01에 승인되고 2026-08-05에 구매확정됐습니다."));

        var out = service().assemble("ORD-D1").orElseThrow();

        assertThat(out.sections()).extracting(DisputeEvidence.Section::name)
                .containsExactly("거래 성립", "대금 흐름", "이행 증빙", "환불·취소 이력",
                        "대사 결과", "이전 분쟁", "운영자 조치");
        // 주문·결제 두 줄이 거래 성립으로 간다
        assertThat(out.sections().get(0).lines()).hasSize(2);
        // 이행 증빙에 에스크로 한 줄
        assertThat(out.sections().get(2).lines()).hasSize(1);
        // 없는 것은 비운 채로 두고 이름을 남긴다
        assertThat(out.gaps()).contains("환불·취소 이력", "대사 결과", "운영자 조치");
        assertThat(counted("has_gaps")).isEqualTo(1);
    }

    @Test
    @DisplayName("요약이 지어낸 숫자를 쓰면 요약만 버리고 항목은 그대로 낸다")
    void dropsOnlyNarrativeWhenFigureIsUnsourced() {
        // 3,000원은 사실 목록에 없다
        when(narrator.narrate(any())).thenReturn(Optional.of(
                "주문 50,000원 중 3,000원이 미수취로 다퉈지고 있습니다."));

        var out = service().assemble("ORD-D1").orElseThrow();

        assertThat(out.narrative()).as("지어낸 숫자가 든 요약은 안 나간다").isNull();
        assertThat(out.sections().get(0).lines()).as("항목은 살아 있어야 한다").isNotEmpty();
        assertThat(counted("narrative_unsourced")).isEqualTo(1);
    }

    @Test
    @DisplayName("요약이 금액을 빠뜨려도 요약만 버린다")
    void dropsOnlyNarrativeWhenAmountMissing() {
        when(narrator.narrate(any())).thenReturn(Optional.of(
                "주문이 승인되고 구매확정됐습니다."));

        var out = service().assemble("ORD-D1").orElseThrow();

        assertThat(out.narrative()).isNull();
        assertThat(out.sections()).isNotEmpty();
        assertThat(counted("narrative_dropped_amounts")).isEqualTo(1);
    }

    @Test
    @DisplayName("사실이 없으면 증빙을 만들지 않는다 — 없는 것으로 증빙을 만들지 않는다")
    void refusesWhenNoFacts() {
        when(draftService.factsFor(anyString(), any()))
                .thenReturn(new FactPack("ORD-D2", List.of(), Set.of(), Set.of(), null, true));

        assertThat(service().assemble("ORD-D2")).isEmpty();
        assertThat(counted("no_facts")).isEqualTo(1);
    }

    @Test
    @DisplayName("요약을 만드는 구현이 아예 없어도 항목은 나간다")
    void worksWithoutNarrator() {
        var withoutNarrator = new DisputeEvidenceService(draftService, new DisputeEvidenceAssembler(),
                new NumericProvenanceGuard(), new AmountCoverageGuard(),
                Optional.empty(), registry);

        var out = withoutNarrator.assemble("ORD-D1").orElseThrow();

        assertThat(out.narrative()).isNull();
        assertThat(out.sections().get(0).lines()).isNotEmpty();
    }

    @Test
    @DisplayName("요약 문장에 출처 낱말이 들어갔다고 그 항목이 되지 않는다")
    void doesNotMatchSourceWordInsideSummaryText() {
        var assembled = new DisputeEvidenceAssembler().assemble(new FactPack("ORD-D3",
                // 가운데 칸은 ORDER 인데 요약 문장에 PAYMENT 라는 낱말이 들어 있다
                List.of("2026-08-01 · ORDER · PAYMENT 실패 안내를 발송함"),
                Set.of(), Set.of(LocalDate.of(2026, 8, 1)), null, true), null);

        assertThat(assembled.sections().get(0).lines()).hasSize(1);   // 거래 성립(ORDER)에만 든다
    }
}
