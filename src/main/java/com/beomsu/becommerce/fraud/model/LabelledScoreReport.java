package com.beomsu.becommerce.fraud.model;

import com.beomsu.becommerce.dispute.DisputeOutcomePort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 섀도로 낸 점수를 <b>차지백 라벨로 채점한다.</b>
 *
 * <p>docs/27 5-2 절의 조건 A·B 는 우리가 만든 코퍼스에서 잰 값이다. 그 코퍼스의 라벨은
 * 우리가 정의한 패턴이라 <b>실 트래픽에서도 그렇다는 근거가 못 된다.</b> 이 보고서가
 * 그 자리를 메운다. 실제 분쟁 결과를 정답으로 놓고 같은 지표를 다시 낸다.
 *
 * <p><b>지금은 표본이 없다.</b> 차지백이 안 들어왔으면 채점할 것도 없다. 그래도 만들어 두는
 * 이유는 상황 3.4 에서 적은 것과 같다. 라벨이 도착하는 날 <b>채점기를 새로 만드는 게 아니라
 * 이미 돌고 있어야</b> 하고, 그때까지 채점기 자체가 회귀 테스트로 검증돼 있어야 한다.
 *
 * <p><b>이 수치는 실제보다 좋게 나온다.</b> 카드 소유자가 못 알아챈 부정은 차지백으로 안 와서
 * 영원히 정상으로 남는다. 놓친 것을 못 세니 재현율이 부풀린다. 없앨 수 있는 편향이 아니라
 * <b>적어 둘 편향</b>이다.
 */
@Service
public class LabelledScoreReport {

    private final CardTransactionRepository transactions;
    private final DisputeOutcomePort disputes;
    private final FraudRiskModel model;

    @Value("${fds.model.window.hours:24}")
    private int windowHours;

    @Value("${fds.model.window.max-rows:200}")
    private int windowMaxRows;

    @Value("${fds.amount.threshold:1000000}")
    private long amountThreshold;

    /** 이 점수 위를 부정으로 본다. 코퍼스 학습에서 정상 오탐 5% 가 되던 지점이다. */
    @Value("${fds.model.threshold:0.539601}")
    private double threshold;

    public LabelledScoreReport(CardTransactionRepository transactions, DisputeOutcomePort disputes,
                               FraudRiskModel model) {
        this.transactions = transactions;
        this.disputes = disputes;
        this.model = model;
    }

    /**
     * 최근 {@code days} 일 안에 승패가 갈린 분쟁을 정답으로 놓고 모델을 채점한다.
     *
     * @param limit 볼 분쟁 수 상한. 상황 2.3 과 같은 이유로 상한을 받는다
     */
    @Transactional(readOnly = true)
    public Report scoreAgainstDisputes(int days, int limit) {
        Instant since = Instant.now().minus(Duration.ofDays(days));
        long caught = 0, missed = 0, falseAlarm = 0, legitimate = 0, unscorable = 0;

        for (var outcome : disputes.settledSince(since, limit)) {
            FraudLabel label = FraudLabel.of(outcome);
            if (label == FraudLabel.UNKNOWN) {
                continue;
            }
            Double risk = riskOf(outcome.orderNo());
            if (risk == null) {
                // 거래 이력이 없다. 표를 만들기 전의 결제라 창을 못 만든다.
                unscorable++;
                continue;
            }
            boolean flagged = risk >= threshold;
            if (label == FraudLabel.FRAUD) {
                if (flagged) caught++; else missed++;
            } else {
                legitimate++;
                if (flagged) falseAlarm++;
            }
        }
        return new Report(caught, missed, falseAlarm, legitimate, unscorable, threshold);
    }

    /** 그 주문의 결제를 창과 함께 다시 채점한다. 이력이 없으면 {@code null}. */
    private Double riskOf(String orderNo) {
        var rows = transactions.findByOrderNo(orderNo);
        if (rows.isEmpty()) {
            return null;
        }
        var target = rows.getFirst();
        Instant since = target.getOccurredAt().minus(Duration.ofHours(windowHours));
        var window = new ArrayList<TxnRecord>();
        // 그 결제 <이후>에 일어난 것은 그때 몰랐던 사실이다. 거르는 자리를 조회로 옮겼다 —
        // 자바에서 거르면 행 상한을 미래 건이 먼저 먹어 과거 창이 짧아진다.
        for (var row : transactions.findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                target.getCardKey(), since, target.getOccurredAt(), PageRequest.of(0, windowMaxRows))) {
            window.add(row.toRecord());
        }
        Collections.reverse(window);
        return model.risk(SequenceFeatures.of(window, target.toRecord(), amountThreshold));
    }

    /**
     * 채점 결과.
     *
     * @param unscorable 라벨은 있는데 거래 이력이 없어 못 잰 건. <b>0 으로 묻으면 안 된다</b>
     */
    public record Report(long caught, long missed, long falseAlarm, long legitimate,
                         long unscorable, double threshold) {

        public long labelledFraud() {
            return caught + missed;
        }

        /** 부정 중 잡은 비율. 표본이 없으면 {@code null} — 0 으로 채우면 다 놓친 것처럼 읽힌다. */
        public Double recall() {
            return labelledFraud() == 0 ? null : (double) caught / labelledFraud();
        }

        /** 정상 중 잘못 올린 비율. */
        public Double falseAlarmRate() {
            return legitimate == 0 ? null : (double) falseAlarm / legitimate;
        }

        /**
         * 이 수치를 근거로 쓸 만한가.
         *
         * <p>부정 라벨이 {@link #MIN_LABELLED_FRAUD} 건은 있어야 한다. 3건에서 2건을 잡은 것은
         * 우연과 구별되지 않는다. 상황 6.1 의 {@code MIN_JUDGED} 와 같은 종류의 바닥이다.
         */
        public boolean usable() {
            return labelledFraud() >= MIN_LABELLED_FRAUD;
        }

        /** 이 밑으로는 비율을 근거로 쓰지 않는다. */
        public static final long MIN_LABELLED_FRAUD = 20;
    }
}
