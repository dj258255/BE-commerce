package com.beomsu.becommerce.assist;

import com.beomsu.becommerce.assist.draft.TemplateDraftAdapter;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.DraftPort;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.ResolveCause;

import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.TimelineEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 초안 평가 하네스 — <b>모델을 고르기 전에 잣대를 먼저 만든다.</b>
 *
 * <p>Klarna가 AI 상담을 되돌린 이유는 기술이 아니라 지표 설계 순서였다. CEO가 인정한 원인은
 * <i>"비용이 지나치게 지배적인 평가 요소였고, 그래서 품질이 낮아졌다"</i>는 것이다.
 * 그래서 여기서는 <b>사실 정합성(groundedness)을 먼저</b> 재고, 속도·비용은 그다음이다.
 *
 * <p><b>왜 지금 만드나</b>: 지금은 템플릿 구현뿐이라 통과가 당연하다. 그래서 만들 수 있다 —
 * 정답이 명확한 동안 잣대를 고정해 두는 것이다. 모델이 붙고 나서 만들면
 * 잣대가 그 모델에 맞춰 휘어진다.
 *
 * <p><b>하네스가 잣대 노릇을 하는지도 검증한다.</b> 일부러 값을 지어내는 가짜 구현을
 * 같이 돌려서, 좋은 구현과 나쁜 구현을 실제로 <b>가르는지</b>를 확인한다.
 * 전부 통과시키는 하네스는 잣대가 아니다.
 */
class DraftEvalHarnessTest {

    // ── 골든 케이스: 정답이 명확한 입력들 ────────────────────────────────
    private static Map<String, FactPack> goldens() {
        Map<String, FactPack> g = new LinkedHashMap<>();
        g.put("승인만", new FactPack("ORD-1",
                List.of("2026-08-30 · PAYMENT · 결제 승인"),
                Set.of(10_000L), Set.of(LocalDate.of(2026, 8, 30)), null, true));
        g.put("승인+부분취소", new FactPack("ORD-2",
                List.of("2026-08-28 · PAYMENT · 결제 승인",
                        "2026-08-30 · PAYMENT · 부분취소"),
                Set.of(10_000L, 3_000L),
                Set.of(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 30)),
                "PARTIAL_CANCEL_NOT_REFLECTED (LIKELY) — 취소액과 차액이 일치", true));
        g.put("타임라인 불완전", new FactPack("ORD-3",
                List.of("2026-08-30 · ORDER · 주문 생성 — 항목 2개"),
                Set.of(5_000L), Set.of(LocalDate.of(2026, 8, 30)), null, false));
        g.put("사실 없음", new FactPack("ORD-4",
                List.of(), Set.of(), Set.of(), null, true));
        return g;
    }

    // ── 가짜 구현: 모델이 실제로 저지르는 실패를 흉내낸다 ──────────────────
    /** 그럴듯한 수수료를 지어낸다. 실제 모델의 대표적 실패다. */
    private static final DraftPort INVENTS_AMOUNT = new DraftPort() {
        public Optional<String> draft(FactPack f) {
            return f.empty() ? Optional.empty()
                    : Optional.of("확인 결과 수수료 270원이 차감되었습니다.");
        }
        public String name() { return "fixture:invents-amount"; }
    };

    /** 날짜를 그럴듯하게 옮긴다. 경계 문제를 서술로 덮어버리는 형태다. */
    private static final DraftPort INVENTS_DATE = new DraftPort() {
        public Optional<String> draft(FactPack f) {
            return f.empty() ? Optional.empty()
                    : Optional.of("2026-01-01에 처리되었습니다.");
        }
        public String name() { return "fixture:invents-date"; }
    };

    private record Score(String name, int cases, int drafted, int grounded, List<String> reasons) {
        double coverage()   { return cases == 0 ? 0 : (double) drafted / cases; }
        double groundedness() { return drafted == 0 ? 1.0 : (double) grounded / drafted; }
    }

    private Score evaluate(DraftPort port) {
        NumericProvenanceGuard guard = new NumericProvenanceGuard();
        Map<String, FactPack> cases = goldens();
        int drafted = 0, grounded = 0;
        List<String> reasons = new java.util.ArrayList<>();

        for (Map.Entry<String, FactPack> e : cases.entrySet()) {
            Optional<String> text = port.draft(e.getValue());
            if (text.isEmpty()) {
                continue;
            }
            drafted++;
            List<String> bad = guard.verify(text.get(), e.getValue());
            if (bad.isEmpty()) {
                grounded++;
            } else {
                reasons.add(e.getKey() + ": " + bad);
            }
        }
        return new Score(port.name(), cases.size(), drafted, grounded, reasons);
    }

    private void report(Score s) {
        System.out.printf("  %-24s 초안 %d/%d (%.0f%%)  사실정합 %d/%d (%.0f%%)%n",
                s.name(), s.drafted(), s.cases(), s.coverage() * 100,
                s.grounded(), s.drafted(), s.groundedness() * 100);
        s.reasons().forEach(r -> System.out.println("      └ " + r));
    }

    @Test
    @DisplayName("템플릿 구현은 사실 정합성 100% — 출처에 없는 값을 쓰지 않는다")
    void templateIsFullyGrounded() {
        Score s = evaluate(new TemplateDraftAdapter());
        System.out.println("[초안 평가]");
        report(s);

        assertThat(s.groundedness())
                .as("템플릿은 사실을 옮기기만 하므로 100%여야 한다")
                .isEqualTo(1.0);
        assertThat(s.drafted())
                .as("사실이 있는 케이스에는 초안이 나와야 한다")
                .isEqualTo(3);      // '사실 없음'은 초안을 만들지 않는 게 맞다
    }

    @Test
    @DisplayName("하네스가 좋은 구현과 나쁜 구현을 실제로 가른다 — 전부 통과시키면 잣대가 아니다")
    void harnessDiscriminates() {
        Score good = evaluate(new TemplateDraftAdapter());
        Score badAmount = evaluate(INVENTS_AMOUNT);
        Score badDate = evaluate(INVENTS_DATE);

        System.out.println("[잣대 검증]");
        report(good);
        report(badAmount);
        report(badDate);

        assertThat(badAmount.groundedness())
                .as("지어낸 금액은 전부 걸려야 한다")
                .isZero();
        assertThat(badDate.groundedness())
                .as("지어낸 날짜는 전부 걸려야 한다")
                .isZero();
        assertThat(good.groundedness())
                .as("좋은 구현은 나쁜 구현보다 높아야 한다")
                .isGreaterThan(badAmount.groundedness());
    }

    @Test
    @DisplayName("재료가 없으면 초안을 만들지 않는다 — 빈 값이 지어낸 문장보다 낫다")
    void producesNothingWithoutFacts() {
        FactPack empty = goldens().get("사실 없음");
        assertThat(new TemplateDraftAdapter().draft(empty)).isEmpty();
    }

    @Test
    @DisplayName("타임라인이 불완전하면 초안이 그 사실을 밝힌다")
    void disclosesIncompleteTimeline() {
        FactPack incomplete = goldens().get("타임라인 불완전");
        String text = new TemplateDraftAdapter().draft(incomplete).orElseThrow();
        assertThat(text).contains("가져오지 못했습니다");
    }

    @Test
    @DisplayName("원인 제안은 '제안'으로 표시된다 — 확정으로 읽히면 안 된다")
    void causeHintIsMarkedAsSuggestion() {
        FactPack withHint = goldens().get("승인+부분취소");
        String text = new TemplateDraftAdapter().draft(withHint).orElseThrow();
        assertThat(text).contains("추정 원인").contains("제안일 뿐");
    }

    @Test
    @DisplayName("분류기가 계산한 근거 금액은 검증을 통과한다 — 실측에서 4건 중 2건이 오반려됐던 자리")
    void classifierEvidenceAmountsAreFacts() {
        // 실제 대사에서 나온 근거 문장 그대로. 8,888 과 270 은 타임라인 금액이 아니라
        // 분류기가 <계산>한 값이다. 코드가 낸 숫자이므로 초안에 나와도 된다.
        OrderTimeline timeline = new OrderTimeline("ORD-9",
                List.of(TimelineEntry.of(
                        java.time.LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.PAYMENT, "PAID", "결제 승인", 10_000L)),
                List.of());
        // 규칙이 계산한 값을 <구조로> 들고 온다. 예전에는 이 문장을 정규식으로 파싱했다.
        CauseSuggestion suggestion = new CauseSuggestion(ResolveCause.SUSPECTED_TAMPERING,
                CauseSuggestion.Confidence.WEAK,
                "차액 8,888원이 수수료(270원)로도 취소로도 설명되지 않는다. 취소 이력이 없다",
                java.util.Set.of(8_888L, 270L));

        FactPack facts = FactPack.from(timeline, suggestion);
        String draft = new TemplateDraftAdapter().draft(facts).orElseThrow();

        assertThat(new NumericProvenanceGuard().verify(draft, facts))
                .as("분류기 근거의 숫자를 반려하면, 확신이 가장 높은 규칙과 "
                        + "초안이 가장 필요한 건에서 초안이 사라진다")
                .isEmpty();
        assertThat(facts.amounts()).contains(8_888L, 270L, 10_000L);
    }

    @Test
    @DisplayName("근거에 없는 숫자는 여전히 걸린다 — 허용 범위를 넓힌 게 아니다")
    void stillCatchesInventionAfterWidening() {
        OrderTimeline timeline = new OrderTimeline("ORD-9", List.of(), List.of());
        FactPack facts = FactPack.from(timeline, CauseSuggestion.decisive(
                ResolveCause.FEE_CALCULATION_DIFF, "차액 8,888원", 8_888L));

        assertThat(new NumericProvenanceGuard().verify("확인 결과 1,234원이 차감되었습니다.", facts))
                .anySatisfy(m -> assertThat(m).contains("출처에 없는 금액"));
    }

    @Test
    @DisplayName("사실 문장 안의 금액을 인용해도 통과한다 — 실제 모델이 찾아낸 자리")
    void amountsInsideFactSentencesAreQuotable() {
        // 타임라인 요약이 "내부 10,000 / 외부 9,730" 처럼 두 값을 문장에 담는다.
        // entry.amount() 에는 하나만 들어가므로, 문장을 읽고 인용하면 나머지가 반려됐다.
        OrderTimeline timeline = new OrderTimeline("ORD-7",
                List.of(TimelineEntry.of(
                        java.time.LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "MISMATCH",
                        "대사 AMOUNT_MISMATCH — 내부 10,000 / 외부 9,730", 10_000L, java.util.List.of(10000L, 9730L),
                        java.util.List.<java.time.LocalDate>of())),
                List.of());
        FactPack facts = FactPack.from(timeline,
                null);

        assertThat(facts.amounts()).contains(10_000L, 9_730L);
        assertThat(new NumericProvenanceGuard().verify("정산 파일에는 9,730원으로 기록돼 있습니다.", facts))
                .isEmpty();
    }

    @Test
    @DisplayName("사실 문장 안의 미래 날짜도 인용할 수 있다 — 반복 실측에서 반려의 100%가 이 값이었다")
    void datesInsideFactSentencesAreQuotable() {
        // 에스크로 사실이 "자동해제 예정 2026-09-06T..." 을 담는다. 사건 시각(at)은 8/30이지만
        // 문장이 말하는 날짜는 9/6이다. entry.at() 만 모으면 후자가 빠진다.
        OrderTimeline timeline = new OrderTimeline("ORD-8",
                List.of(TimelineEntry.of(
                        java.time.LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.ESCROW, "HELD",
                        "에스크로 보류 — 자동해제 예정 2026-09-06T11:06:29Z", 10_000L,
                        java.time.LocalDate.of(2026, 9, 6))),
                List.of());
        FactPack facts = FactPack.from(timeline,
                null);

        assertThat(facts.dates())
                .contains(java.time.LocalDate.of(2026, 8, 30), java.time.LocalDate.of(2026, 9, 6));
        assertThat(new NumericProvenanceGuard().verify("2026-09-06에 자동 해제될 예정입니다.", facts)).isEmpty();
        assertThat(new NumericProvenanceGuard().verify("2027-01-01에 해제됩니다.", facts))
                .as("사실에 없는 날짜는 여전히 걸려야 한다")
                .isNotEmpty();
    }
}
