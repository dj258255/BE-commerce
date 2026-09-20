package com.beomsu.becommerce.fraud.model;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;

/**
 * 모델 점수를 <b>계산해서 기록만 한다.</b> 심사 큐에 넣지 않는다.
 *
 * <p><b>왜 섀도인가</b>: docs/27 5-2 절이 적은 켤 조건 넷 중 셋이 아직 안 넘었다. 그런데
 * 켤지 정하려면 실 트래픽에서의 성적이 필요하고, 그걸 얻는 유일한 방법이 <b>돌리되 아무것도
 * 안 하는 것</b>이다. Uber 는 이것을 Evaluate 모드라 부르고, 규칙을 활성화하기 전에 발동
 * 빈도와 동작을 관찰한다.
 *
 * <p><b>홀드아웃을 같이 둔다.</b> 일부 건은 점수를 아예 안 낸다. 나중에 모델을 켰을 때 그
 * 구간이 <b>기준선</b>이 된다. Stripe 도 일부 결제의 위험 점수를 바꿔 모델 성능을 잰다.
 * 켠 다음에 홀드아웃을 만들면 이미 비교할 이전이 없다. <b>켜기 전에 있어야 한다.</b>
 *
 * <p><b>주문번호 해시로 가른다.</b> 난수로 고르면 같은 주문이 재배달될 때마다 홀드아웃에
 * 들었다 말았다 한다. 그러면 그 건이 어느 쪽이었는지 사후에 못 짚는다.
 *
 * <p><b>여기서 예외를 올리지 않는다.</b> 섀도는 관찰이라 실패해도 결제나 사후 탐지가 멈추면
 * 안 된다. 상황 6.1 의 fail-open 과 같은 자리인데, 다른 점은 <b>여기는 아무것도 안 막고
 * 있어서</b> 꺼져도 잃는 것이 관측뿐이라는 것이다.
 */
@Slf4j
@Service
public class ShadowRiskScorer {

    /** 섀도 판정 결과. 알림과 대시보드가 이 이름을 본다. */
    public static final String METRIC = "fds.model.shadow.outcome";
    /** 점수 분포. 임계를 나중에 정하려면 분포가 쌓여 있어야 한다. */
    public static final String SCORE_METRIC = "fds.model.shadow.score";

    private final CardTransactionRepository transactions;
    private final FraudRiskModel model;
    private final MeterRegistry registry;

    @Value("${fds.model.shadow.enabled:true}")
    private boolean enabled;

    /** 창을 얼마나 되돌아볼지. 너무 길면 amountToMedian 이 평생 평균이 된다. */
    @Value("${fds.model.window.hours:24}")
    private int windowHours;

    /** 창 조회 상한. 상황 2.3 에서 배치 조회에 건 것과 같은 이유다. */
    @Value("${fds.model.window.max-rows:200}")
    private int windowMaxRows;

    /** 홀드아웃 비율(퍼센트). 이 비율만큼은 점수를 아예 안 낸다. */
    @Value("${fds.model.holdout.percent:5}")
    private int holdoutPercent;

    /** 고액 규칙의 임계. 임계 바로 밑 구간을 재려면 그 값이 있어야 한다. */
    @Value("${fds.amount.threshold:1000000}")
    private long amountThreshold;

    public ShadowRiskScorer(CardTransactionRepository transactions, FraudRiskModel model,
                            MeterRegistry registry) {
        this.transactions = transactions;
        this.model = model;
        this.registry = registry;
    }

    /**
     * 이 결제를 섀도로 채점한다.
     *
     * @return 낸 점수. 껐거나 홀드아웃이거나 실패했으면 빈 값
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Double> score(String cardKey, String orderNo, TxnRecord current) {
        if (!enabled) {
            return java.util.Optional.empty();
        }
        if (inHoldout(orderNo)) {
            count("holdout");
            return java.util.Optional.empty();
        }
        try {
            List<TxnRecord> window = windowOf(cardKey, current);
            var features = SequenceFeatures.of(window, current, amountThreshold);
            double risk = model.risk(features);

            DistributionSummary.builder(SCORE_METRIC)
                    .publishPercentiles(0.5, 0.95, 0.99)
                    .register(registry)
                    .record(risk);
            count("scored");

            // 어느 피처가 밀었는지 같이 남긴다. 점수만 남기면 나중에 왜 그랬는지 못 짚는다.
            if (model instanceof LogisticFraudRiskModel logistic) {
                log.debug("[fds-shadow] order={} risk={} window={} top={}",
                        orderNo, "%.3f".formatted(risk), window.size(),
                        String.join(", ", logistic.topContributors(features, 3)));
            }
            return java.util.Optional.of(risk);
        } catch (RuntimeException e) {
            // 관찰이 결제나 사후 탐지를 멈추면 안 된다.
            count("failed");
            log.warn("[fds-shadow] 채점 실패 order={}", orderNo, e);
            return java.util.Optional.empty();
        }
    }

    /**
     * 창을 만든다. <b>이번 건을 포함한다.</b>
     *
     * <p>저장이 먼저 돌아 이번 건이 이미 들어 있을 수도, 저장이 실패해 없을 수도 있다.
     * 없으면 붙인다. 둘 다 다루지 않으면 창의 마지막 건이 있다 없다 해서 {@code escalation}
     * 이 같은 결제에 두 값을 낸다.
     */
    private List<TxnRecord> windowOf(String cardKey, TxnRecord current) {
        Instant since = current.at().minus(Duration.ofHours(windowHours));
        // 이번 건보다 <나중> 거래는 그때 몰랐던 사실이다. 창에 들어오면 미래를 보고 채점한다.
        var rows = transactions.findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                cardKey, since, current.at(), PageRequest.of(0, windowMaxRows));

        List<TxnRecord> window = new ArrayList<>(rows.size() + 1);
        for (var row : rows) {
            window.add(row.toRecord());
        }
        Collections.reverse(window);   // 최신순으로 받았으니 되돌린다
        boolean hasCurrent = window.stream()
                .anyMatch(t -> t.at().equals(current.at()) && t.amount() == current.amount());
        if (!hasCurrent) {
            window.add(current);
        }
        return window;
    }

    /**
     * 홀드아웃인가. <b>주문번호로 결정한다.</b>
     *
     * <p>난수를 쓰면 재배달마다 결과가 바뀌어 그 건이 어느 쪽이었는지 사후에 못 짚는다.
     * CRC32 는 암호용이 아니지만 여기서는 고르게 흩기만 하면 되고, <b>같은 입력에 같은 답</b>이
     * 라는 성질이 필요한 전부다.
     */
    boolean inHoldout(String orderNo) {
        if (holdoutPercent <= 0 || orderNo == null) {
            return false;
        }
        CRC32 crc = new CRC32();
        crc.update(orderNo.getBytes(StandardCharsets.UTF_8));
        return crc.getValue() % 100 < holdoutPercent;
    }

    private void count(String outcome) {
        Counter.builder(METRIC).tag("outcome", outcome).register(registry).increment();
    }
}
