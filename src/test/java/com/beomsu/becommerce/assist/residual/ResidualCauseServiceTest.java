package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.DraftService;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ResidualCauseService}의 가드 여섯 개를 각각 고정한다.
 *
 * <p><b>가드마다 테스트가 하나씩 있어야 한다.</b> 모델이 붙은 뒤에 검사를 만들면
 * 그 검사가 실제로 무언가를 막는지 확인할 방법이 없다. 상담 초안에서 같은 실수를
 * 한 번 했다. 손으로 만든 입력만 통과시켜 놓고 검증됐다고 적었다.
 */
class ResidualCauseServiceTest {

    private DraftService draftService;
    private ResidualCausePort port;
    private ResidualCauseService service;
    private MeterRegistry registry;

    /** 켠 유형이 성립하는 사실 묶음 — 대사 결과가 <외부에만 있음>이라 가드 8을 통과한다. */
    private static final FactPack FACTS = new FactPack("ORD-1",
            List.of("2026-08-30 · PAYMENT · 결제 승인 100,000원",
                    "2026-08-30 · RECONCILIATION · 대사 EXTERNAL_ONLY (거래일 2026-08-30) — 내부 없음 / 외부 100,000"),
            Set.of(100_000L, 3_000L),
            Set.of(LocalDate.of(2026, 8, 30)),
            null, true, true);

    /** 같은 사실인데 대사 결과가 <내부에만 있음> — 방향이 반대라 이 원인일 수 없다. */
    private static final FactPack INTERNAL_ONLY_FACTS = new FactPack("ORD-2",
            List.of("2026-08-30 · PAYMENT · 결제 승인 100,000원",
                    "2026-08-30 · RECONCILIATION · 대사 INTERNAL_ONLY (거래일 2026-08-30) — 내부 100,000 / 외부 없음"),
            Set.of(100_000L),
            Set.of(LocalDate.of(2026, 8, 30)),
            null, true, false);

    @BeforeEach
    void setUp() {
        draftService = mock(DraftService.class);
        port = mock(ResidualCausePort.class);
        registry = new SimpleMeterRegistry();
        when(draftService.factsFor(anyString(), anyLong())).thenReturn(FACTS);
        when(port.name()).thenReturn("stub");

        service = new ResidualCauseService(draftService, new NumericProvenanceGuard(),
                Optional.of(port), registry);
        ReflectionTestUtils.setField(service, "minConfidence", 70);
    }

    private void modelSays(ResolveCause cause, int confidence, String rationale) {
        when(port.suggest(any())).thenReturn(
                Optional.of(new ResidualSuggestion(cause, rationale, confidence, Set.of())));
    }

    private double counted(String outcome) {
        var c = registry.find("assist.residual.outcome").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("가드 1 — 규칙이 결정적 후보를 냈으면 모델을 아예 부르지 않는다")
    void skipsWhenRulesDecided() {
        List<CauseSuggestion> rules =
                List.of(CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF, "차액이 수수료와 일치", 270L));

        assertThat(service.suggest("ORD-1", 1L, rules)).isEmpty();

        verify(port, never()).suggest(any());
        assertThat(counted("skipped_rules_decided")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 1 — 배제법으로 낸 WEAK 뿐이면 부른다. 그건 '모르겠다'는 뜻이다")
    void callsWhenOnlyWeakSuggestions() {
        // 금액 불일치가 수수료도 취소도 아닐 때 규칙은 SUSPECTED_TAMPERING 을 WEAK 로 낸다.
        // 근거 문구가 "설명되지 않는다"이므로 위변조 판정이 아니라 미상이다.
        List<CauseSuggestion> weakOnly = List.of(new CauseSuggestion(
                ResolveCause.SUSPECTED_TAMPERING, CauseSuggestion.Confidence.WEAK,
                "차액이 수수료로도 취소로도 설명되지 않는다", Set.of(7_000L)));
        modelSays(ResolveCause.INTERNAL_RECORD_LOST, 90, "내부 기록이 없습니다");

        assertThat(service.suggest("ORD-1", 1L, weakOnly)).isPresent();
        assertThat(counted("skipped_rules_decided")).isZero();
    }

    @Test
    @DisplayName("가드 3 — 위변조 의심은 모델이 골라도 버린다")
    void rejectsTampering() {
        modelSays(ResolveCause.SUSPECTED_TAMPERING, 99, "금액이 조작된 것으로 보입니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("forbidden_cause")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 3 — OTHER 도 원인으로 받지 않는다")
    void rejectsOther() {
        modelSays(ResolveCause.OTHER, 95, "분류할 수 없습니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("forbidden_cause")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 4 — 신뢰도가 임계 미만이면 기권으로 처리한다")
    void abstainsBelowThreshold() {
        modelSays(ResolveCause.INTERNAL_RECORD_LOST, 69, "내부에 기록이 없습니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("below_threshold")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 5 — 근거에 출처 없는 금액이 있으면 제안을 통째로 버린다")
    void rejectsUnsourcedFigures() {
        modelSays(ResolveCause.INTERNAL_RECORD_LOST, 90, "차액 8,888원이 확인됩니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("unsourced_figures")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 5 — 사실에 있는 금액만 쓰면 통과한다")
    void acceptsSourcedFigures() {
        modelSays(ResolveCause.INTERNAL_RECORD_LOST, 90, "차액 3,000원이 확인되고 내부 기록이 없습니다");

        Optional<ResidualSuggestion> out = service.suggest("ORD-1", 1L, List.of());

        assertThat(out).isPresent();
        assertThat(out.get().cause()).isEqualTo(ResolveCause.INTERNAL_RECORD_LOST);
        assertThat(counted("suggested")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 8 — 대사 결과가 <내부에만 있음>이면 같은 답이라도 버린다")
    void dropsWhenReconResultContradictsCause() {
        // 홀드아웃에서 세 모델이 이 상황에서 45건 중 45건을 INTERNAL_RECORD_LOST 로 냈다.
        // 프롬프트에 "내부 기록 금액이 있으면 이 원인이 아니다"라고 적어 뒀는데도 그랬다.
        when(draftService.factsFor(anyString(), anyLong())).thenReturn(INTERNAL_ONLY_FACTS);
        modelSays(ResolveCause.INTERNAL_RECORD_LOST, 98, "내부 기록이 없습니다");

        Optional<ResidualSuggestion> out = service.suggest("ORD-2", 2L, List.of());

        // 신뢰도 98 이라 가드 4 는 통과하고, 켠 유형이라 가드 7 도 통과한다. 여기서 막아야 한다.
        assertThat(out).isEmpty();
        assertThat(counted("contradicts_recon_result")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 6 — 사실이 불완전하면 모델을 아예 부르지 않는다")
    void skipsWhenFactsIncomplete() {
        // 프롬프트에 "불완전합니다"를 적어 줘도 모델이 무시하고 단정하는 것을
        // 홀드아웃에서 확인했다. 기권을 부탁하는 대신 부를 수 없게 막는다.
        FactPack incomplete = new FactPack("ORD-1", List.of("결제 승인 100,000원"),
                Set.of(100_000L), Set.of(LocalDate.of(2026, 8, 30)), null, false);
        when(draftService.factsFor(anyString(), anyLong())).thenReturn(incomplete);

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();

        verify(port, never()).suggest(any());
        assertThat(counted("incomplete_facts")).isEqualTo(1);
    }

    @Test
    @DisplayName("가드 7 — 아직 켜지 않은 유형은 버린다")
    void rejectsTypeNotEnabled() {
        // 엔진이 만든 272건에서 유형별로 재니 PARTIAL_CANCEL_NOT_REFLECTED 는 세 모델
        // 모두 60건 중 0건이었다. 전체 정확도가 아니라 유형별로 켜고 끈다.
        modelSays(ResolveCause.PARTIAL_CANCEL_NOT_REFLECTED, 95, "부분취소로 보입니다");

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("type_not_enabled")).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 스스로 기권하면 그대로 기권으로 집계한다")
    void recordsModelAbstention() {
        when(port.suggest(any())).thenReturn(Optional.empty());

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("abstained")).isEqualTo(1);
    }

    @Test
    @DisplayName("모델이 터져도 확정 경로를 막지 않는다")
    void survivesModelFailure() {
        when(port.suggest(any())).thenThrow(new IllegalStateException("모델 죽음"));

        assertThat(service.suggest("ORD-1", 1L, List.of())).isEmpty();
        assertThat(counted("error")).isEqualTo(1);
    }

    @Test
    @DisplayName("포트가 없으면 사실 조회조차 하지 않는다")
    void noPortMeansNoWork() {
        var noPort = new ResidualCauseService(draftService, new NumericProvenanceGuard(),
                Optional.empty(), registry);

        assertThat(noPort.suggest("ORD-1", 1L, List.of())).isEmpty();
        verify(draftService, never()).factsFor(anyString(), anyLong());
    }

    @Test
    @DisplayName("템플릿 구현은 항상 기권한다 — 이것이 기본값이 곧 꺼짐인 이유다")
    void templateAlwaysAbstains() {
        var withTemplate = new ResidualCauseService(draftService, new NumericProvenanceGuard(),
                Optional.of(new TemplateResidualAdapter()), registry);
        ReflectionTestUtils.setField(withTemplate, "minConfidence", 70);

        assertThat(withTemplate.suggest("ORD-1", 1L, List.of())).isEmpty();
    }
}
