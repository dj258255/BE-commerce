package com.beomsu.becommerce.dispute.internal;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 이의제기 대응 기한 게이지.
 *
 * <p><b>왜 필요한가</b>: 이 큐는 이 시스템에서 <b>외부에 하드 기한이 있는 유일한 사람 대기열</b>이다.
 * 이상거래 심사는 밀리면 위험이 커지지만 늦게라도 할 수 있다. 이의제기는 기한을 넘기면
 * <b>다툴 기회 자체가 사라지고 그대로 패소</b>가 되어 대금이 회수된다. 늦은 대응과 안 한 대응이 같다.
 *
 * <p>그런데 기한을 넘기는 순간에는 <b>아무 에러도 안 난다.</b> 행은 그대로 `OPEN` 이고,
 * 어느 날 카드사가 패소를 통보할 뿐이다. 사람이 달력을 보고 있지 않으면 못 막는다.
 *
 * <p><b>남은 시간을 낸다.</b> 이상거래 큐가 "가장 오래 기다린 건의 나이"를 보는 것과 방향이 반대다.
 * 거기는 지날수록 나빠지므로 경과를 보고, 여기는 기한까지 남은 시간이 줄어드는 것이 위험이다.
 * 이미 지난 건은 음수로 나온다 — 0 으로 깎으면 "지났다"와 "빠듯하다"가 같아 보인다.
 */
@Component
public class DisputeDeadlineMetrics {

    public DisputeDeadlineMetrics(MeterRegistry meterRegistry, DisputeRepository repository) {
        Gauge.builder("dispute.open.count", this,
                        m -> repository.findByStatus(DisputeStatus.OPEN).size())
                .description("아직 증빙을 제출하지 않은 이의제기 건수")
                .register(meterRegistry);

        Gauge.builder("dispute.nearest.deadline.seconds", this,
                        m -> nearestDeadlineSeconds(repository))
                .description("가장 임박한 대응 기한까지 남은 시간(초). 이미 지났으면 음수, 대상이 없으면 0")
                .register(meterRegistry);
    }

    private double nearestDeadlineSeconds(DisputeRepository repository) {
        List<Dispute> open = repository.findByStatus(DisputeStatus.OPEN);
        Instant now = Instant.now();
        return open.stream()
                .map(Dispute::getRespondByDeadline)
                .filter(java.util.Objects::nonNull)
                .mapToDouble(d -> Duration.between(now, d).toSeconds())
                .min()
                .orElse(0.0);
    }
}
