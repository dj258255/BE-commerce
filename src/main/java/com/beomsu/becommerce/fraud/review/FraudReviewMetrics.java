package com.beomsu.becommerce.fraud.review;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 이상거래 사후 심사 큐 게이지.
 *
 * <p><b>왜 이 지표가 필요한가</b>: 이상거래 판정은 통과·추가 인증·사람 검토·차단 넷으로 갈리고,
 * 그중 사람 검토가 이 큐다. 심사가 밀리면 사기로 의심되는 결제가 <b>그대로 정산으로 넘어간다.</b>
 * 미확정 결제가 떠 있는 것과 성격이 같은데, 이쪽에는 알림이 없었다.
 *
 * <p><b>두 값을 따로 낸다</b>: 큐 깊이만 보면 적체를 놓친다. 열 건이 방금 들어온 것과
 * 한 건이 이틀 묵은 것은 위험이 다르다. 업계에서도 큐 알림은 "가장 오래된 항목이 임계를 넘을 때"를
 * 기준으로 잡는다. 그래서 건수와 <b>최장 대기 시간</b>을 함께 노출한다.
 */
@Component
public class FraudReviewMetrics {

    public FraudReviewMetrics(MeterRegistry meterRegistry, FraudReviewRepository repository) {
        Gauge.builder("fraud.review.pending.count", this,
                        m -> repository.countByStatus(FraudReviewStatus.PENDING))
                .description("사람 심사를 기다리는 이상거래 건수")
                .register(meterRegistry);

        Gauge.builder("fraud.review.oldest.age.seconds", this,
                        m -> repository.findOldestCreatedAt(FraudReviewStatus.PENDING)
                                .map(t -> (double) Duration.between(t, Instant.now()).toSeconds())
                                .orElse(0.0))
                .description("가장 오래 기다린 이상거래 심사 건의 대기 시간(초). 없으면 0")
                .register(meterRegistry);

        // 오탐률. 사람이 처리한 건 중 <정상이었다>고 판정한 비율이다.
        //
        // 라벨은 이미 쌓이고 있었다. 심사를 APPROVED 로 닫으면 규칙이 잡았는데 정상이었다는
        // 뜻이고, REJECTED 면 실제 부정이었다는 뜻이다. <세는 사람이 없었을 뿐이다.>
        //
        // 이 값이 없으면 규칙을 조일지 풀지를 감으로 정하게 된다. 임계를 낮추면 큐가 늘고,
        // 늘어난 큐가 오탐이면 사람 시간만 쓰는데 그것을 구분할 근거가 없었다.
        //
        // 분모는 <처리된 건>이다. PENDING 을 넣으면 심사가 밀릴수록 오탐률이 떨어지는 것처럼
        // 보인다. 아직 판정이 없는 건은 오탐도 정탐도 아니다.
        Gauge.builder("fraud.review.false.positive.ratio", this,
                        m -> {
                            long approved = repository.countByStatus(FraudReviewStatus.APPROVED);
                            long rejected = repository.countByStatus(FraudReviewStatus.REJECTED);
                            long judged = approved + rejected;
                            return judged == 0 ? 0.0 : (double) approved / judged;
                        })
                .description("사람이 처리한 이상거래 심사 중 정상으로 판정된 비율. 판정 이력이 없으면 0")
                .register(meterRegistry);

        // 분모도 함께 낸다. 비율만 보면 2건 중 1건이 50% 로 잡혀 규칙을 잘못 손대게 된다.
        Gauge.builder("fraud.review.judged.count", this,
                        m -> (double) (repository.countByStatus(FraudReviewStatus.APPROVED)
                                + repository.countByStatus(FraudReviewStatus.REJECTED)))
                .description("사람이 정상/부정으로 판정을 끝낸 이상거래 심사 건수")
                .register(meterRegistry);
    }
}
