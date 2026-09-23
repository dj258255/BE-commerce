package com.beomsu.becommerce.reconciliation;

import static org.mockito.ArgumentMatchers.eq;

import static org.mockito.ArgumentMatchers.any;

import org.springframework.data.domain.Pageable;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationService;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResult;
import com.beomsu.becommerce.reconciliation.internal.ReconResultType;
import com.beomsu.becommerce.reconciliation.internal.InternalRecordRepository;
import com.beomsu.becommerce.reconciliation.internal.InternalRecord;
import com.beomsu.becommerce.reconciliation.internal.ExternalRecord;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.cause.CauseClassifier;
import com.beomsu.becommerce.payment.PaymentTimelineFacts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 잔여 후보 평가용 케이스를 <b>대사 엔진을 돌려서</b> 만든다.
 *
 * <p><b>왜 손으로 안 쓰나.</b> 지금까지 27건을 손으로 썼는데, 그 27건은
 * <b>케이스 작성자와 프롬프트 작성자와 평가자가 전부 나</b>였다. 문장 틀도 거의 같아서,
 * 개수를 250으로 늘려도 검정력은 안 늘어난다. 표본 1,000개가 의미상 비슷하면
 * 실제로는 수백 개의 독립 표본만큼의 힘밖에 없다는 것이 알려져 있다.
 *
 * <p>그래서 조건 축만 정하고 <b>사실 문장은 엔진이 만들게</b> 한다. 내부 기록과 외부 기록을
 * 조건 조합으로 심고 {@link ReconciliationService#reconcile}을 태우면, 불일치 판정과 금액이
 * 코드에서 나온다. 케이스 작성이 빠지는 만큼 편향이 하나 줄어든다.
 *
 * <p>그리고 <b>규칙 분류기가 답한 건은 뺀다.</b> 잔여 후보는 규칙이 아무것도 못 낸 건에만
 * 붙는 기능이라, 규칙이 답하는 것까지 넣으면 모델을 쉬운 문제로 채점하는 셈이다.
 *
 * <p>테스트가 아니라 생성기다. {@code -Dcorpus.generate=true} 일 때만 돈다.
 * CI 에서 매번 돌 이유가 없고, 산출물은 파일로 나가 모델 평가 스크립트가 읽는다.
 */
@EnabledIfSystemProperty(named = "corpus.generate", matches = "true")
class ResidualCorpusGenerator {

    private static final LocalDate D = LocalDate.of(2026, 7, 15);
    private static final long FEE_BPS = 270;

    /** 조건 축. 이걸 조합해 케이스를 만든다. */
    private record Axis(String label, long amount, String kind) {
    }

    @Test
    @DisplayName("대사 엔진을 돌려 잔여 후보 케이스를 뽑는다")
    void generate() throws IOException {
        var internalRepo = mock(InternalRecordRepository.class);
        var resultRepo = mock(ReconciliationResultRepository.class);
        var facts = mock(PaymentTimelineFacts.class);
        when(facts.findByOrderNo(anyString())).thenReturn(List.of());
        // 결제 상태는 케이스마다 심는다. 비워 두면 <결제 기록 없는 대사 기록>이라는
        // 없는 세계가 된다 — InternalRecord 는 결제 이벤트에서 만들어진다.
        Map<String, PaymentTimelineFacts.PaymentState> states = new HashMap<>();
        when(facts.findState(anyString())).thenAnswer(
                inv -> Optional.ofNullable(states.get(inv.getArgument(0, String.class))));
        when(resultRepo.findByOrderNoOrderByIdAsc(anyString())).thenReturn(List.of());

        var service = new ReconciliationService(internalRepo, resultRepo);
        var classifier = new CauseClassifier(facts, resultRepo, FEE_BPS);

        // 금액을 넓게 흔든다. 같은 자릿수만 쓰면 "의미상 비슷한 표본"이 된다.
        long[] amounts = {1_200, 9_900, 33_000, 88_800, 150_000, 470_000, 1_030_000, 12_500_000};
        // 차액의 성격을 흔든다 — 수수료와 맞는 것, 안 맞는 것, 부분취소 크기, 랜덤
        String[] kinds = {"fee_exact", "fee_off_by_one", "partial_small", "partial_large",
                          "unexplained", "internal_only", "external_only", "duplicate"};

        List<Map<String, Object>> corpus = new ArrayList<>();
        List<Map<String, Object>> ruleAnswered = new ArrayList<>();

        int n = 0;
        for (long amount : amounts) {
            for (String kind : kinds) {
                for (int variant = 0; variant < 5; variant++) {
                    String orderNo = "GEN-%03d".formatted(++n);
                    List<ExternalRecord> external = new ArrayList<>();
                    List<InternalRecord> internal = new ArrayList<>();
                    String expected = seed(orderNo, amount, kind, variant, internal, external);
                    states.put(orderNo, stateFor(orderNo, amount, kind, variant));

                    when(internalRepo.findByTradeDate( eq(D), any(Pageable.class))).thenReturn(internal);
                    List<ReconciliationResult> out = service.reconcile(D, external);

                    for (ReconciliationResult r : out) {
                        if (r.getResult() == ReconResultType.MATCHED) continue;
                        var rules = classifier.suggest(r);
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("orderNo", r.getOrderNo());
                        row.put("kind", kind);
                        row.put("expected", expected);
                        row.put("result", r.getResult().name());
                        row.put("internal", r.getInternalAmount());
                        row.put("external", r.getExternalAmount());
                        boolean decided = rules.stream()
                                .anyMatch(x -> x.confidence() == CauseSuggestion.Confidence.DECISIVE);
                        row.put("ruleCount", rules.size());
                        row.put("decided", decided);
                        row.put("weakOnly", !rules.isEmpty() && !decided);
                        if (!rules.isEmpty()) {
                            row.put("ruleTop", rules.getLast().cause().name());
                        }
                        // 결정적 후보가 있으면 이 기능의 대상이 아니다
                        (decided ? ruleAnswered : corpus).add(row);
                    }
                }
            }
        }

        Path dir = Path.of(System.getProperty("corpus.out", "build/corpus"));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("residual-corpus.json"), toJson(corpus));

        System.out.printf("%n  생성 %d건 · 규칙이 답함 %d건 · 잔여(모델 대상) %d건%n",
                corpus.size() + ruleAnswered.size(), ruleAnswered.size(), corpus.size());
        Map<String, Long> byKind = new TreeMap<>();
        for (var r : corpus) byKind.merge((String) r.get("kind"), 1L, Long::sum);
        byKind.forEach((k, v) -> System.out.printf("    %-16s %d%n", k, v));
    }

    /** 그 주문의 결제 상태. 대사 기록이 있으면 결제도 있다. */
    private PaymentTimelineFacts.PaymentState stateFor(String orderNo, long amount,
                                                       String kind, int variant) {
        Instant at = D.atTime(3 + variant, 0).toInstant(java.time.ZoneOffset.UTC);
        long canceled = switch (kind) {
            case "partial_small" -> amount / 10;
            case "partial_large" -> amount / 2;
            default -> 0;
        };
        int cancelCount = canceled > 0 ? 1 : 0;
        return new PaymentTimelineFacts.PaymentState(
                amount, amount - canceled, cancelCount, "DONE", at);
    }

    /** 조건 하나를 심고, 사람이라면 무엇을 골랐을지를 돌려준다. */
    private String seed(String orderNo, long amount, String kind, int variant,
                        List<InternalRecord> internal, List<ExternalRecord> external) {
        Instant at = D.atTime(3 + variant, 0).toInstant(java.time.ZoneOffset.UTC);
        long fee = amount * FEE_BPS / 10_000;

        switch (kind) {
            case "fee_exact" -> {                       // 차액이 수수료와 정확히 일치
                internal.add(InternalRecord.of(orderNo, amount, at));
                external.add(ExternalRecord.of(orderNo, amount - fee));
                return "FEE_CALCULATION_DIFF";
            }
            case "fee_off_by_one" -> {                  // 수수료에 가깝지만 안 맞음
                internal.add(InternalRecord.of(orderNo, amount, at));
                external.add(ExternalRecord.of(orderNo, amount - fee - 1 - variant));
                return "ABSTAIN";
            }
            case "partial_small", "partial_large" -> {  // 부분취소분만큼 차이
                long cancel = kind.equals("partial_small") ? amount / 10 : amount / 2;
                internal.add(InternalRecord.of(orderNo, amount - cancel, at));
                external.add(ExternalRecord.of(orderNo, amount));
                return "PARTIAL_CANCEL_NOT_REFLECTED";
            }
            case "unexplained" -> {                     // 무엇으로도 설명 안 되는 차액
                internal.add(InternalRecord.of(orderNo, amount, at));
                external.add(ExternalRecord.of(orderNo, amount - (7_777 + variant * 13L)));
                return "ABSTAIN";
            }
            case "internal_only" -> {
                // 내부에만 있고 <어느 거래일 파일에서도> 못 찾은 건.
                // 이 목록의 일곱 원인 중 맞는 것이 없다. PG_FILE_DELAY 는 "다음 파일에
                // 포함됨"이고 INTERNAL_RECORD_LOST 는 방향이 반대다.
                // 그래서 정답은 기권이다.
                internal.add(InternalRecord.of(orderNo, amount, at));
                return "ABSTAIN";
            }
            case "external_only" -> {                   // 내부에 없음
                external.add(ExternalRecord.of(orderNo, amount));
                return "INTERNAL_RECORD_LOST";
            }
            case "duplicate" -> {                       // 같은 거래가 여러 행
                internal.add(InternalRecord.of(orderNo, amount, at));
                for (int i = 0; i <= variant % 2; i++) external.add(ExternalRecord.of(orderNo, amount));
                external.add(ExternalRecord.of(orderNo, amount));
                return "DUPLICATE_RECORD";
            }
            default -> throw new IllegalArgumentException(kind);
        }
    }

    private String toJson(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < rows.size(); i++) {
            sb.append("  {");
            var it = rows.get(i).entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                sb.append('"').append(e.getKey()).append("\": ");
                Object v = e.getValue();
                sb.append(v instanceof String s ? "\"" + s + "\"" : String.valueOf(v));
                if (it.hasNext()) sb.append(", ");
            }
            sb.append('}').append(i < rows.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("]\n").toString();
    }
}
