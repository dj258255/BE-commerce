package com.beomsu.becommerce.reconciliation;

import com.beomsu.becommerce.reconciliation.internal.ReconciliationResultRepository;
import com.beomsu.becommerce.reconciliation.internal.ReconciliationResult;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.cause.CauseClassifier;
import com.beomsu.becommerce.payment.PaymentTimelineFacts;
import com.beomsu.becommerce.payment.PaymentTimelineFacts.PaymentState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 원인 <b>커버리지</b> 실험 — 규칙이 8종 중 몇 종을 실제로 가르는가.
 *
 * <p><b>왜 이게 필요했나</b>: README 와 ADR-012 는 "8종 중 6종이 산수로 결정된다"고 적어뒀는데,
 * 코드에는 규칙이 5개뿐이었다. {@code TIMEZONE_BOUNDARY}는 enum 에만 있고 아무도 내지 않았다.
 * ADR-012 표에서는 그걸 <b>자동 확정 후보</b>로까지 올려놨다.
 * <b>문서가 코드보다 한 칸 앞서 있었고, 아무 테스트도 그걸 잡지 못했다.</b>
 *
 * <p>개별 규칙 테스트({@link CauseClassifierTest})는 "이 입력에 이 원인이 나오는가"를 본다.
 * 그래서 <b>아무도 안 내는 원인은 영원히 안 보인다.</b> 이 하네스는 반대 방향으로 본다 —
 * 원인 목록 전체를 놓고 <b>어느 것이 비어 있는가</b>를 센다.
 *
 * <p><b>이것이 AI 논의의 근거가 된다.</b> "AI 를 어디에 쓸 것인가"는 취향이 아니라
 * 규칙이 못 가르는 자리가 어디인지의 문제다. 그 자리를 <b>세어서</b> 말해야 한다.
 */
class CauseCoverageHarnessTest {

    private static final long FEE_BPS = 270;                      // 2.7%
    private static final LocalDate TRADE_DATE = LocalDate.of(2026, 8, 30);
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    private final PaymentTimelineFacts paymentFacts = mock(PaymentTimelineFacts.class);
    private final ReconciliationResultRepository repository = mock(ReconciliationResultRepository.class);
    private final CauseClassifier classifier = new CauseClassifier(paymentFacts, repository, FEE_BPS);

    /** 어느 시각의 승인이었는지까지 정해야 경계 판정을 태울 수 있다. */
    private void payment(long amount, long balance, int cancelCount, LocalDateTime approvedAt) {
        when(paymentFacts.findState(anyString())).thenReturn(Optional.of(
                new PaymentState(amount, balance, cancelCount, "DONE",
                        approvedAt.atZone(KST).toInstant())));
    }

    private void noOtherTradeDates() {
        when(repository.findByOrderNoOrderByIdAsc(anyString())).thenReturn(List.of());
    }

    /** 같은 주문이 다른 거래일에 외부 기록으로 잡힌 상황. */
    private void alsoSeenOn(LocalDate other, long externalAmount) {
        when(repository.findByOrderNoOrderByIdAsc(anyString())).thenReturn(List.of(
                ReconciliationResult.externalOnly(other, "ord-1", externalAmount)));
    }

    // ── 8종을 각각 만들어내는 시나리오 ───────────────────────────────────
    private Map<String, Runnable> scenarios(List<ReconciliationResult> target) {
        Map<String, Runnable> s = new LinkedHashMap<>();

        s.put("수수료 차감", () -> {
            noOtherTradeDates();
            payment(100_000, 100_000, 0, TRADE_DATE.atTime(14, 0));
            target.add(ReconciliationResult.amountMismatch(TRADE_DATE, "ord-1", 100_000, 97_300));
        });
        s.put("부분취소 미반영", () -> {
            noOtherTradeDates();
            payment(100_000, 70_000, 1, TRADE_DATE.atTime(14, 0));
            target.add(ReconciliationResult.amountMismatch(TRADE_DATE, "ord-1", 100_000, 70_000));
        });
        s.put("PG 파일 지연", () -> {
            alsoSeenOn(TRADE_DATE.plusDays(3), 100_000);          // 인접일이 아니다
            payment(100_000, 100_000, 0, TRADE_DATE.atTime(14, 0));
            target.add(ReconciliationResult.internalOnly(TRADE_DATE, "ord-1", 100_000));
        });
        s.put("거래일 경계", () -> {
            alsoSeenOn(TRADE_DATE.plusDays(1), 100_000);          // 인접일 + 자정 근처 승인
            payment(100_000, 100_000, 0, TRADE_DATE.atTime(23, 47));
            target.add(ReconciliationResult.internalOnly(TRADE_DATE, "ord-1", 100_000));
        });
        s.put("승인 직후 취소", () -> {
            noOtherTradeDates();
            payment(100_000, 0, 1, TRADE_DATE.atTime(14, 0));
            target.add(ReconciliationResult.internalOnly(TRADE_DATE, "ord-1", 100_000));
        });
        s.put("내부 기록 유실", () -> {
            noOtherTradeDates();
            when(paymentFacts.findState(anyString())).thenReturn(Optional.empty());
            target.add(ReconciliationResult.externalOnly(TRADE_DATE, "ord-1", 100_000));
        });
        s.put("설명 안 되는 차액", () -> {
            noOtherTradeDates();
            payment(100_000, 100_000, 0, TRADE_DATE.atTime(14, 0));
            target.add(ReconciliationResult.amountMismatch(TRADE_DATE, "ord-1", 100_000, 88_888));
        });
        return s;
    }

    private record Row(String scenario, ResolveCause cause,
                       CauseSuggestion.Confidence confidence, String evidence) {}

    private List<Row> runAll() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, Runnable> e : scenarios(new ArrayList<>()).entrySet()) {
            List<ReconciliationResult> target = new ArrayList<>();
            scenarios(target).get(e.getKey()).run();
            List<CauseSuggestion> got = classifier.suggest(target.get(0));
            if (got.isEmpty()) {
                rows.add(new Row(e.getKey(), null, null, "제안 없음"));
            } else {
                CauseSuggestion top = got.get(0);
                rows.add(new Row(e.getKey(), top.cause(), top.confidence(), top.evidence()));
            }
        }
        return rows;
    }

    @Test
    @DisplayName("커버리지 표 — 규칙이 어느 원인을 가르고 어느 자리가 비는지 센다")
    void reportCoverage() {
        List<Row> rows = runAll();

        System.out.println("\n[원인 커버리지]");
        System.out.printf("  %-16s %-30s %-10s%n", "시나리오", "최상위 제안", "확신");
        for (Row r : rows) {
            System.out.printf("  %-16s %-30s %-10s%n", r.scenario(),
                    r.cause() == null ? "—" : r.cause(),
                    r.confidence() == null ? "—" : r.confidence());
        }

        // 등급을 나눠 센다. "제안이 나왔다"와 "결정됐다"는 다르다 —
        // SUSPECTED_TAMPERING 은 배제법으로 <남은> 것이지 산수로 결정된 게 아니다.
        // 이 구분을 안 하면 "7종을 가른다"고 잘못 말하게 된다(실제로 처음에 그렇게 셌다).
        List<ResolveCause> decided = tier(rows, CauseSuggestion.Confidence.DECISIVE);
        List<ResolveCause> likely = tier(rows, CauseSuggestion.Confidence.LIKELY);
        List<ResolveCause> byElimination = tier(rows, CauseSuggestion.Confidence.WEAK);
        List<ResolveCause> none = java.util.Arrays.stream(ResolveCause.values())
                .filter(c -> !decided.contains(c) && !likely.contains(c)
                        && !byElimination.contains(c))
                .toList();

        System.out.printf("%n  산수로 결정   (DECISIVE) : %d종 %s%n", decided.size(), decided);
        System.out.printf("  정황으로 제안 (LIKELY)   : %d종 %s%n", likely.size(), likely);
        System.out.printf("  배제로 남음   (WEAK)     : %d종 %s   ← 사람이 봐야 한다%n",
                byElimination.size(), byElimination);
        System.out.printf("  규칙 없음               : %d종 %s   ← AI 를 논할 자리%n",
                none.size(), none);

        // 문서의 주장과 대조한다. 어긋나면 코드나 문서 중 하나가 틀린 것이다.
        // 이 단언이 없어서 TIMEZONE_BOUNDARY 가 규칙 없이 문서에만 있는 상태로 남아 있었다.
        assertThat(concat(decided, likely))
                .as("README·ADR-012 가 '8종 중 6종이 산수나 조회로 결정된다'고 적어뒀다. "
                        + "규칙을 늘리거나 줄이면 문서도 같이 고쳐라")
                .hasSize(6);
        assertThat(byElimination)
                .as("위변조 의심은 결정되는 게 아니라 남는 것이다. 확신 등급이 올라가면 안 된다")
                .containsExactly(ResolveCause.SUSPECTED_TAMPERING);
    }

    private static List<ResolveCause> tier(List<Row> rows, CauseSuggestion.Confidence c) {
        return rows.stream().filter(r -> r.confidence() == c)
                .map(Row::cause).distinct().sorted().toList();
    }

    private static List<ResolveCause> concat(List<ResolveCause> a, List<ResolveCause> b) {
        List<ResolveCause> all = new ArrayList<>(a);
        all.addAll(b);
        return all;
    }

    @Test
    @DisplayName("거래일 경계를 파일 지연과 구분한다 — 대응이 다르므로 같은 이름으로 부르면 안 된다")
    void distinguishesBoundaryFromDelay() {
        List<Row> rows = runAll();

        Row boundary = rows.stream().filter(r -> r.scenario().equals("거래일 경계")).findFirst().orElseThrow();
        Row delay = rows.stream().filter(r -> r.scenario().equals("PG 파일 지연")).findFirst().orElseThrow();

        assertThat(boundary.cause())
                .as("인접일 + 자정 근처 승인은 경계다. 기다린다고 맞춰지지 않는다")
                .isEqualTo(ResolveCause.TIMEZONE_BOUNDARY);
        assertThat(delay.cause())
                .as("사흘 뒤에 잡힌 건 경계가 아니라 지연이다")
                .isEqualTo(ResolveCause.PG_FILE_DELAY);
        assertThat(boundary.evidence()).contains("자정");
    }

    @Test
    @DisplayName("낮 시간 승인은 인접일에 있어도 경계로 부르지 않는다")
    void daytimeApprovalIsNotBoundary() {
        List<ReconciliationResult> target = new ArrayList<>();
        alsoSeenOn(TRADE_DATE.plusDays(1), 100_000);
        payment(100_000, 100_000, 0, TRADE_DATE.atTime(14, 0));    // 낮 2시
        target.add(ReconciliationResult.internalOnly(TRADE_DATE, "ord-1", 100_000));

        assertThat(classifier.suggest(target.get(0)))
                .as("낮 거래가 날짜를 넘어갈 일은 없다. 전부 경계로 몰면 흔한 지연을 잘못 부른다")
                .noneMatch(c -> c.cause() == ResolveCause.TIMEZONE_BOUNDARY);
    }
}
