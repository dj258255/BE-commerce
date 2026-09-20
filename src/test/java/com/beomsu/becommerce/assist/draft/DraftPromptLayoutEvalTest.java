package com.beomsu.becommerce.assist.draft;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 실험 14 — <b>금액이 빠지면 되묻는 것</b>이 값을 하는지 셋을 나란히 잰다(13 문서).
 *
 * <p>같은 하네스로 실험 13(프롬프트 배치)을 먼저 쟀고 12건에서 1건 차이라 안 켰다. 그때 초안을
 * 열어 보고 찾은 진짜 병목이 <b>금액 누락</b>이라, 이번에는 그 자리를 고쳐서 다시 잰다.
 *
 * <p><b>판정 기준은 결과를 보기 전에 문서에 적었다.</b> 여기서 재는 것은 자동으로 잴 수 있는
 * 넷(용어 누출·출처 없는 숫자·금액 결손·응답시간)까지고, <b>편집률은 사람이 3단계 블라인드
 * 리뷰를 마쳐야</b> 나온다. 자동 지표에서 떨어지면 사람 리뷰까지 가지 않는다.
 *
 * <p><b>표본은 새로 만들었다.</b> 기존 골든 4건은 프롬프트를 고치는 데 쓴 것이라 평가에서 뺀다.
 * 같은 건을 보며 고친 뒤 그 건의 점수가 좋아진 것을 근거로 삼는 함정은 실험 3에서 이미 겪었다.
 */
@Tag("eval")
class DraftPromptLayoutEvalTest {

    private static final String MODEL = System.getProperty("eval.model", "qwen3:8b");
    private static final String BASE = System.getProperty("eval.ollama", "http://localhost:11434");

    /**
     * 평가용 새 사례 12건. 골든 4건과 겹치지 않는다.
     *
     * <p><b>주문번호는 운영과 같은 ULID 형식이다.</b> 처음엔 `EV-01` 처럼 읽기 좋은 값을 썼는데,
     * 모델이 초안에 주문번호를 적으면 `CustomerGlossary` 가 `EV` 를 내부 코드로 잡아
     * <b>용어 누출이 나지 않는데 난 것으로 세어졌다.</b> 운영 주문번호는 `Ulid.generate()` 라
     * 첫 글자가 타임스탬프(숫자)이고, 탐지기 정규식은 `[A-Z]` 로 시작해야 잡으므로 안 걸린다.
     * 탐지기가 아니라 표본을 고쳤다. 채점 기준을 결과에 맞춰 고치면 안 된다.
     */
    private static Map<String, FactPack> cases() {
        Map<String, FactPack> c = new LinkedHashMap<>();
        c.put("승인 후 전액취소", new FactPack("01K4Q7ZJ8MRT3V6XN2WYB5DCEA",
                List.of("2026-09-01 · PAYMENT · 결제 승인", "2026-09-03 · PAYMENT · 전액취소"),
                Set.of(24_000L), Set.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 3)), null, true));
        c.put("부분취소 두 번", new FactPack("01K4Q7ZJ9NST4W7YP3XZC6EFGH",
                List.of("2026-09-02 · PAYMENT · 결제 승인", "2026-09-04 · PAYMENT · 부분취소",
                        "2026-09-05 · PAYMENT · 부분취소"),
                Set.of(50_000L, 12_000L, 8_000L),
                Set.of(LocalDate.of(2026, 9, 2), LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 5)),
                "PARTIAL_CANCEL_NOT_REFLECTED (LIKELY)", true));
        c.put("미확정 진행중", new FactPack("01K4Q7ZJAPTV5X8ZQ4Y2D7FJKM",
                List.of("2026-09-06 · PAYMENT · 승인 요청", "2026-09-06 · PAYMENT · 응답 없음(미확정)"),
                Set.of(31_500L), Set.of(LocalDate.of(2026, 9, 6)), null, true));
        c.put("가상계좌 입금대기", new FactPack("01K4Q7ZJBQVW6Y9ZR5Z3E8GKNP",
                List.of("2026-09-05 · PAYMENT · 가상계좌 발급"),
                Set.of(70_000L), Set.of(LocalDate.of(2026, 9, 5)), null, true));
        c.put("대사 차액 원인미상", new FactPack("01K4Q7ZJCRWX7ZA0S6A4F9HMQR",
                List.of("2026-09-01 · PAYMENT · 결제 승인", "2026-09-02 · RECON · 정산 파일과 불일치"),
                Set.of(19_800L, 300L), Set.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)), null, true));
        c.put("내부기록 없음", new FactPack("01K4Q7ZJDSXY8AB1T7B5GAJNRS",
                List.of("2026-09-03 · RECON · 정산 파일에만 있음"),
                Set.of(45_000L), Set.of(LocalDate.of(2026, 9, 3)),
                "INTERNAL_RECORD_LOST (DECISIVE)", true));
        c.put("PG 파일 지연", new FactPack("01K4Q7ZJETYZ9BC2V8C6HBKPST",
                List.of("2026-09-04 · PAYMENT · 결제 승인", "2026-09-07 · RECON · 정산 파일 미도착"),
                Set.of(15_000L), Set.of(LocalDate.of(2026, 9, 4), LocalDate.of(2026, 9, 7)),
                "PG_FILE_DELAY (LIKELY)", true));
        c.put("구독 청구 실패", new FactPack("01K4Q7ZJFVZA0CD3W9D7JCMQTV",
                List.of("2026-09-01 · SUBSCRIPTION · 정기결제 실패", "2026-09-02 · SUBSCRIPTION · 재시도 실패"),
                Set.of(9_900L), Set.of(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 2)), null, true));
        c.put("분쟁 접수", new FactPack("01K4Q7ZJGWAB1DE4X0E8KDNRVW",
                List.of("2026-08-28 · PAYMENT · 결제 승인", "2026-09-06 · DISPUTE · 이의제기 접수"),
                Set.of(120_000L), Set.of(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 6)), null, true));
        c.put("환불 처리중", new FactPack("01K4Q7ZJHXBC2EF5Y1F9MEPSWX",
                List.of("2026-09-02 · PAYMENT · 전액취소", "2026-09-02 · SETTLEMENT · 환불 요청 전달"),
                Set.of(33_000L), Set.of(LocalDate.of(2026, 9, 2)), null, true));
        c.put("타임라인 불완전", new FactPack("01K4Q7ZJJYCD3FG6Z2G0NFQTXY",
                List.of("2026-09-05 · ORDER · 주문 생성"),
                Set.of(6_500L), Set.of(LocalDate.of(2026, 9, 5)), null, false));
        c.put("중복 청구 의심", new FactPack("01K4Q7ZJKZDE4GH7A3H1PGRVYZ",
                List.of("2026-09-03 · PAYMENT · 결제 승인", "2026-09-03 · PAYMENT · 결제 승인"),
                Set.of(27_000L), Set.of(LocalDate.of(2026, 9, 3)), null, true));
        return c;
    }

    private record Score(String name, int n, int jargon, int badNumber, int amountMiss,
                         long p50, long p95, int empty) {
        double pct(int x) { return n == 0 ? 0 : x * 100.0 / n; }
    }

    @Test
    @DisplayName("실험 14 — 금액이 빠지면 되묻는 것이 값을 하는가")
    void compareLayouts() {
        var glossary = new CustomerGlossary();
        var examples = new DraftExamples();
        var numeric = new NumericProvenanceGuard();
        var coverage = new AmountCoverageGuard();

        var template = new TemplateDraftAdapter();
        var current = ollama(new PromptBuilder(glossary, examples, "current"));

        var rows = new ArrayList<Score>();
        rows.add(run("① 템플릿", template, glossary, numeric, coverage, false));
        rows.add(run("② 모델 그대로", current, glossary, numeric, coverage, false));
        rows.add(run("③ 금액 빠지면 되묻기", current, glossary, numeric, coverage, true));

        System.out.printf("%n  모델 %s · 사례 %d건 · 판정 기준은 13 문서 실험 14 에 먼저 적었다%n%n", MODEL, cases().size());
        System.out.printf("  %-12s %8s %10s %10s %9s %9s %7s%n",
                "방식", "용어누출", "출처없는수", "금액결손", "p50", "p95", "빈초안");
        System.out.println("  " + "-".repeat(72));
        for (Score s : rows) {
            System.out.printf("  %-12s %7.0f%% %9.0f%% %9.0f%% %8dms %8dms %6d%n",
                    s.name(), s.pct(s.jargon()), s.pct(s.badNumber()), s.pct(s.amountMiss()),
                    s.p50(), s.p95(), s.empty());
        }
        System.out.println("""

                  통과 조건(넷 다 만족해야 켠다)
                    용어 누출 0% · 출처 없는 숫자 0% · p95 15,000ms 이하
                    편집률은 여기서 못 잰다 — 사람이 3단계 블라인드 리뷰를 마쳐야 나온다
                """);
    }

    private DraftPort ollama(PromptBuilder pb) {
        return new OllamaDraftAdapter(pb, BASE, MODEL, 30);   // 운영과 같은 예산으로 잰다
    }

    private Score run(String name, DraftPort port, CustomerGlossary glossary,
                      NumericProvenanceGuard numeric, AmountCoverageGuard coverage, boolean revise) {
        int jargon = 0, badNumber = 0, amountMiss = 0, empty = 0;
        var times = new ArrayList<Long>();
        for (var e : cases().entrySet()) {
            long t0 = System.nanoTime();
            var text = port.draft(e.getValue());
            times.add(Duration.ofNanos(System.nanoTime() - t0).toMillis());
            if (text.isEmpty() || text.get().isBlank()) { empty++; continue; }
            String draft = text.get();
            if (revise) {
                // 서비스가 하는 것과 같은 절차: 빠진 금액을 근거로 한 번 되묻고,
                // 되물은 결과가 <더 나쁘지 않을 때만> 바꾼다.
                var miss0 = coverage.missing(draft, e.getValue());
                if (!miss0.isEmpty()) {
                    var issues = miss0.stream()
                            .map(a -> "금액 " + java.text.NumberFormat.getNumberInstance(
                                    java.util.Locale.KOREA).format(a) + "원이 초안에 없다")
                            .toList();
                    var fixed = port.revise(e.getValue(), draft, issues);
                    if (fixed.isPresent() && !fixed.get().isBlank()
                            && numeric.verify(fixed.get(), e.getValue()).isEmpty()
                            && coverage.missing(fixed.get(), e.getValue()).size() < miss0.size()) {
                        draft = fixed.get();
                    }
                }
                times.set(times.size() - 1,
                        Duration.ofNanos(System.nanoTime() - t0).toMillis());
            }
            // 가드가 막아 준 것은 초안이 좋아진 것이 아니다. <가드 이전 원문>에서 센다.
            if (!glossary.findJargon(draft).isEmpty()) jargon++;
            if (!numeric.verify(draft, e.getValue()).isEmpty()) badNumber++;
            var miss = coverage.missing(draft, e.getValue());
            if (!miss.isEmpty()) amountMiss++;
            if (Boolean.getBoolean("eval.dump")) {
                System.out.printf("%n  ── %s / %s · 빠진 금액 %s%n%s%n", name, e.getKey(), miss, draft);
            }
        }
        times.sort(Long::compare);
        long p50 = times.get(times.size() / 2);
        long p95 = times.get(Math.min(times.size() - 1, (int) Math.ceil(times.size() * 0.95) - 1));
        return new Score(name, cases().size(), jargon, badNumber, amountMiss, p50, p95, empty);
    }
}
