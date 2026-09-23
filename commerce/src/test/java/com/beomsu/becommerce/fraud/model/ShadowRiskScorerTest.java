package com.beomsu.becommerce.fraud.model;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 섀도 채점을 고정한다. <b>여기서 아무것도 안 막는다는 것이 요점이다.</b>
 *
 * <p>그리고 <b>홀드아웃이 결정적</b>이어야 한다. 난수로 고르면 재배달마다 그 건이 홀드아웃에
 * 들었다 말았다 해서 사후에 어느 쪽이었는지 못 짚는다. 그러면 기준선이 기준선이 아니다.
 */
@DisplayName("섀도 채점 — 점수를 내고 기록만 한다")
class ShadowRiskScorerTest {

    private static final long THRESHOLD = 1_000_000L;

    private CardTransactionRepository transactions;
    private SimpleMeterRegistry registry;
    private ShadowRiskScorer scorer;

    /** 학습 결과 그대로의 가중치. 설정 기본값과 같은 값이라 운영과 같은 점수가 나온다. */
    private static FraudRiskModel model() {
        return new LogisticFraudRiskModel(new double[]{
                2.259489, 0.383182, 2.921201, 7.864491,
                2.225669, 2.693387, -1.296761, 3.102604
        },
                -4.714702);
    }

    @BeforeEach
    void setUp() {
        transactions = mock(CardTransactionRepository.class);
        registry = new SimpleMeterRegistry();
        scorer = new ShadowRiskScorer(transactions, model(), registry);
        ReflectionTestUtils.setField(scorer, "enabled", true);
        ReflectionTestUtils.setField(scorer, "windowHours", 24);
        ReflectionTestUtils.setField(scorer, "windowMaxRows", 200);
        ReflectionTestUtils.setField(scorer, "holdoutPercent", 5);
        ReflectionTestUtils.setField(scorer, "amountThreshold", THRESHOLD);
    }

    @Test
    @DisplayName("판정 시점보다 나중 거래는 창에 안 넣는다 — 학습은 그런 창을 본 적이 없다")
    void windowStopsAtTheScoredPayment() {
        Instant now = Instant.parse("2026-09-10T12:00:00Z");
        givenWindow(List.of());

        var captured = org.mockito.ArgumentCaptor.forClass(Instant.class);
        scorer.score("card-w", scoredOrderNo(), new TxnRecord(10_000L, now, "dev-1", "1.1.1.1", 0));

        org.mockito.Mockito.verify(transactions).findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                anyString(), any(Instant.class), captured.capture(), any(Pageable.class));

        assertThat(captured.getValue())
                .as("상한이 판정 시점이어야 한다. 없으면 재배달로 먼저 저장된 뒤 건이 창에 섞인다")
                .isEqualTo(now);
    }

    private void givenWindow(List<CardTransaction> rows) {
        when(transactions.findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                anyString(), any(Instant.class), any(Instant.class), any(Pageable.class))).thenReturn(rows);
    }

    private static CardTransaction txn(String card, String orderNo, long amount, Instant at) {
        return CardTransaction.of(card, orderNo, amount, 0, null, null, at);
    }

    /** 홀드아웃에 안 드는 주문번호를 찾는다. 해시가 정하므로 값을 골라야 한다. */
    private String scoredOrderNo() {
        for (int i = 0; i < 500; i++) {
            String no = "ORD-" + i;
            if (!scorer.inHoldout(no)) {
                return no;
            }
        }
        throw new AssertionError("홀드아웃 5% 인데 500개 중 하나도 안 걸렸다");
    }

    /** 홀드아웃에 드는 주문번호. */
    private String heldOutOrderNo() {
        for (int i = 0; i < 500; i++) {
            String no = "ORD-" + i;
            if (scorer.inHoldout(no)) {
                return no;
            }
        }
        throw new AssertionError("홀드아웃 5% 인데 500개 중 하나도 안 들었다");
    }

    @Test
    @DisplayName("홀드아웃은 점수를 아예 안 낸다 — 켠 뒤에 만들면 비교할 이전이 없다")
    void holdoutIsNotScored() {
        givenWindow(List.of());
        String held = heldOutOrderNo();

        var risk = scorer.score("card-1", held, new TxnRecord(500_000, Instant.now(), null, null));

        assertThat(risk).isEmpty();
        assertThat(registry.find(ShadowRiskScorer.METRIC).tag("outcome", "holdout").counter())
                .isNotNull();
    }

    @Test
    @DisplayName("홀드아웃은 결정적이다 — 같은 주문번호는 늘 같은 쪽이다")
    void holdoutIsDeterministic() {
        String no = heldOutOrderNo();
        for (int i = 0; i < 50; i++) {
            assertThat(scorer.inHoldout(no)).isTrue();
        }
        String scored = scoredOrderNo();
        for (int i = 0; i < 50; i++) {
            assertThat(scorer.inHoldout(scored)).isFalse();
        }
    }

    @Test
    @DisplayName("홀드아웃 비율이 대략 설정값이다")
    void holdoutRateIsAboutTheConfiguredPercent() {
        long held = 0;
        for (int i = 0; i < 10_000; i++) {
            if (scorer.inHoldout("ORD-2026-" + i)) {
                held++;
            }
        }
        assertThat(held / 10_000.0).isBetween(0.03, 0.07);
    }

    @Test
    @DisplayName("홀드아웃을 0으로 두면 아무것도 안 뺀다")
    void zeroPercentHoldsOutNothing() {
        ReflectionTestUtils.setField(scorer, "holdoutPercent", 0);
        for (int i = 0; i < 200; i++) {
            assertThat(scorer.inHoldout("ORD-" + i)).isFalse();
        }
    }

    @Test
    @DisplayName("소액 시도가 쌓인 카드는 점수가 높다 — 규칙은 이 건을 통과시킨다")
    void cardTestingPatternScoresHigh() {
        Instant base = Instant.parse("2026-06-01T03:00:00Z");
        List<CardTransaction> window = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            window.add(txn("card-t", "ORD-p" + i, 300, base.plus(Duration.ofMinutes(3L * i))));
        }
        givenWindow(window);

        var main = new TxnRecord(800_000, base.plus(Duration.ofMinutes(40)), null, null);
        double risk = scorer.score("card-t", scoredOrderNo(), main).orElseThrow();

        assertThat(risk).isGreaterThan(0.5);
    }

    @Test
    @DisplayName("평범한 카드는 점수가 낮다")
    void ordinaryCardScoresLow() {
        Instant base = Instant.parse("2026-06-01T05:00:00Z");   // KST 14시
        givenWindow(List.of(
                txn("card-n", "ORD-a", 40_000, base),
                txn("card-n", "ORD-b", 55_000, base.plus(Duration.ofHours(2)))));

        var now = new TxnRecord(48_000, base.plus(Duration.ofHours(3)), null, null);
        double risk = scorer.score("card-n", scoredOrderNo(), now).orElseThrow();

        assertThat(risk).isLessThan(0.3);
    }

    @Test
    @DisplayName("꺼 두면 점수를 안 낸다")
    void disabledScoresNothing() {
        ReflectionTestUtils.setField(scorer, "enabled", false);
        givenWindow(List.of());

        assertThat(scorer.score("card-1", scoredOrderNo(),
                new TxnRecord(500_000, Instant.now(), null, null))).isEmpty();
    }

    @Test
    @DisplayName("창 조회가 터져도 예외를 안 올린다 — 관찰이 판정을 멈추면 안 된다")
    void repositoryFailureIsSwallowed() {
        when(transactions.findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                anyString(), any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenThrow(new IllegalStateException("DB 죽음"));

        var risk = scorer.score("card-1", scoredOrderNo(),
                new TxnRecord(500_000, Instant.now(), null, null));

        assertThat(risk).isEmpty();
        assertThat(registry.find(ShadowRiskScorer.METRIC).tag("outcome", "failed").counter())
                .as("조용히 꺼지면 안 된다. 지표에 남아야 한다")
                .isNotNull();
    }

    @Test
    @DisplayName("창에 이번 건이 없으면 붙인다 — 있다 없다 하면 같은 결제가 두 점수를 낸다")
    void currentIsAlwaysInTheWindow() {
        Instant at = Instant.parse("2026-06-01T05:00:00Z");
        givenWindow(List.of(txn("card-x", "ORD-old", 10_000, at.minus(Duration.ofHours(1)))));

        var current = new TxnRecord(900_000, at, null, null);
        double withoutCurrentStored = scorer.score("card-x", scoredOrderNo(), current).orElseThrow();

        // 이번 건이 이미 저장돼 창에 들어 있는 경우
        givenWindow(List.of(
                txn("card-x", "ORD-old", 10_000, at.minus(Duration.ofHours(1))),
                txn("card-x", "ORD-new", 900_000, at)));
        double withCurrentStored = scorer.score("card-x", scoredOrderNo(), current).orElseThrow();

        assertThat(withoutCurrentStored).isEqualTo(withCurrentStored);
    }
}
