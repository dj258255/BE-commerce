package com.beomsu.becommerce.fraud.model;

import com.beomsu.becommerce.dispute.DisputeOutcome;
import com.beomsu.becommerce.dispute.DisputeOutcomePort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 섀도 점수를 차지백 라벨로 채점하는 것을 고정한다.
 *
 * <p><b>표본이 없을 때 무엇을 돌려주는지가 이 테스트의 절반이다.</b> 0 으로 채우면 다 놓친
 * 것처럼 읽히고, 그 숫자를 근거로 모델을 손보게 된다. 상황 6.1 에서 얇은 표본을 비율로 안
 * 내린 것과 같은 문제다.
 */
@DisplayName("라벨 채점 — 차지백을 정답으로 놓고 모델을 잰다")
class LabelledScoreReportTest {

    private static final Instant AT = Instant.parse("2026-06-01T03:00:00Z");
    private static final long THRESHOLD = 1_000_000L;

    private CardTransactionRepository transactions;
    private DisputeOutcomePort disputes;
    private LabelledScoreReport report;

    private static FraudRiskModel model() {
        return new LogisticFraudRiskModel(new double[]{
                2.679502, -0.158154, 2.928409, 8.557328, 3.530378, 2.452604, -0.967388, 2.115038},
                -5.447640);
    }

    @BeforeEach
    void setUp() {
        transactions = mock(CardTransactionRepository.class);
        disputes = mock(DisputeOutcomePort.class);
        report = new LabelledScoreReport(transactions, disputes, model());
        ReflectionTestUtils.setField(report, "windowHours", 24);
        ReflectionTestUtils.setField(report, "windowMaxRows", 200);
        ReflectionTestUtils.setField(report, "amountThreshold", THRESHOLD);
        ReflectionTestUtils.setField(report, "threshold", 0.53);
    }

    private static DisputeOutcome lostFraud(String orderNo) {
        return new DisputeOutcome(orderNo, "CB-" + orderNo, "10.4 Fraud", "LOST", AT);
    }

    private static DisputeOutcome wonFraud(String orderNo) {
        return new DisputeOutcome(orderNo, "CB-" + orderNo, "10.4 Fraud", "WON", AT);
    }

    private static CardTransaction txn(String card, String orderNo, long amount, Instant at) {
        return CardTransaction.of(card, orderNo, amount, 0, null, null, at);
    }

    /** 카드 테스팅 흐름. 소액을 여덟 번 떠본 뒤 본 거래를 한다. */
    private void givenCardTestingFlow(String card, String orderNo) {
        List<CardTransaction> window = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            window.add(txn(card, "ORD-p" + i, 300, AT.plus(Duration.ofMinutes(3L * i))));
        }
        var target = txn(card, orderNo, 800_000, AT.plus(Duration.ofMinutes(40)));
        window.add(target);

        when(transactions.findByOrderNo(orderNo)).thenReturn(List.of(target));
        when(transactions.findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                anyString(), any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(window.reversed());
    }

    @Test
    @DisplayName("라벨이 없으면 비율도 없다 — 0 으로 채우면 다 놓친 것처럼 읽힌다")
    void noLabelsGiveNoRatios() {
        when(disputes.settledSince(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        var r = report.scoreAgainstDisputes(90, 500);

        assertThat(r.labelledFraud()).isZero();
        assertThat(r.recall()).isNull();
        assertThat(r.falseAlarmRate()).isNull();
        assertThat(r.usable()).isFalse();
    }

    @Test
    @DisplayName("부정 라벨이 스물이 안 되면 근거로 안 쓴다")
    void thinLabelSetIsNotUsable() {
        when(disputes.settledSince(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(lostFraud("ORD-1")));
        givenCardTestingFlow("card-t", "ORD-1");

        var r = report.scoreAgainstDisputes(90, 500);

        assertThat(r.labelledFraud()).isEqualTo(1);
        assertThat(r.recall()).isEqualTo(1.0);
        assertThat(r.usable())
                .as("1건에서 다 맞힌 것은 우연과 구별되지 않는다")
                .isFalse();
    }

    @Test
    @DisplayName("이력이 없는 건은 못 잰 것으로 따로 센다 — 0 으로 묻으면 안 된다")
    void missingHistoryIsCountedSeparately() {
        when(disputes.settledSince(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(lostFraud("ORD-old")));
        when(transactions.findByOrderNo("ORD-old")).thenReturn(List.of());

        var r = report.scoreAgainstDisputes(90, 500);

        assertThat(r.unscorable()).isEqualTo(1);
        assertThat(r.labelledFraud()).isZero();
    }

    @Test
    @DisplayName("이긴 부정 분쟁은 정상 쪽으로 센다")
    void wonDisputeCountsAsLegitimate() {
        when(disputes.settledSince(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(wonFraud("ORD-1")));
        givenCardTestingFlow("card-t", "ORD-1");

        var r = report.scoreAgainstDisputes(90, 500);

        assertThat(r.legitimate()).isEqualTo(1);
        assertThat(r.labelledFraud()).isZero();
        // 이 건은 모델이 높게 봤으므로 정상 오탐이 된다
        assertThat(r.falseAlarm()).isEqualTo(1);
        assertThat(r.falseAlarmRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("그 결제 뒤에 일어난 거래는 창에 안 넣는다 — 미래를 보고 채점하면 안 된다")
    void futureTransactionsAreExcludedFromTheWindow() {
        var target = txn("card-f", "ORD-1", 100_000, AT);
        // 결제 <뒤>에 소액 시도가 잔뜩 있다. 넣으면 microCount 가 튀어 점수가 올라간다.
        List<CardTransaction> after = new ArrayList<>();
        after.add(target);
        for (int i = 0; i < 10; i++) {
            after.add(txn("card-f", "ORD-later" + i, 200, AT.plus(Duration.ofMinutes(5L * (i + 1)))));
        }
        when(transactions.findByOrderNo("ORD-1")).thenReturn(List.of(target));
        // 목이 조회 규약을 지킨다. 거르는 자리가 자바에서 조회로 옮겨 갔으므로,
        // 목이 상한을 무시하면 <실제로는 안 오는 행>을 돌려주며 통과시켜 버린다.
        when(transactions.findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                anyString(), any(Instant.class), any(Instant.class), any(Pageable.class)))
                .thenAnswer(inv -> {
                    Instant until = inv.getArgument(2);
                    return after.stream().filter(t -> !t.getOccurredAt().isAfter(until))
                            .sorted(java.util.Comparator.comparing(CardTransaction::getOccurredAt).reversed())
                            .toList();
                });
        when(disputes.settledSince(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(lostFraud("ORD-1")));

        var r = report.scoreAgainstDisputes(90, 500);

        // 창에 이번 건 하나뿐이라 소액 신호가 없다. 놓친 것으로 세야 정직하다.
        assertThat(r.missed())
                .as("뒤에 일어난 소액 시도를 창에 넣으면 안 잡을 건을 잡은 것으로 세게 된다")
                .isEqualTo(1);

        // 상한을 실제로 그 결제 시각으로 줬는지. 위 목이 규약을 지켜도 본코드가 안 주면 소용없다.
        var until = org.mockito.ArgumentCaptor.forClass(Instant.class);
        org.mockito.Mockito.verify(transactions).findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                anyString(), any(Instant.class), until.capture(), any(Pageable.class));
        assertThat(until.getValue()).isEqualTo(AT);
    }

    @Test
    @DisplayName("진행 중인 분쟁은 건너뛴다")
    void openDisputeIsSkipped() {
        when(disputes.settledSince(any(Instant.class), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of(new DisputeOutcome("ORD-1", "CB-1", "10.4", "OPEN", null)));

        var r = report.scoreAgainstDisputes(90, 500);

        assertThat(r.labelledFraud()).isZero();
        assertThat(r.legitimate()).isZero();
        assertThat(r.unscorable()).isZero();
    }
}
