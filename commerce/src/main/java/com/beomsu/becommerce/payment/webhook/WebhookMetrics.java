package com.beomsu.becommerce.payment.webhook;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * FAILED 웹훅 적체 게이지.
 *
 * <p>보류({@link WebhookEventStatus#PENDING_PAYMENT}) 재시도가 소진돼 {@link WebhookEventStatus#FAILED}
 * 가 된 웹훅은 <b>다시 읽히지 않는 종단 행</b>이다(자동 재처리 없음). 그래서 이 행들은 지표로만
 * 보인다 — 쌓여도 아무도 모르면 결제 상태가 PG 와 어긋난 채 방치된다.
 *
 * <p><b>개수와 나이를 함께 낸다.</b> 몇 건인지는 적체의 크기이고, 가장 오래된 건의 나이는 적체가
 * <b>멈춰 있는지</b>를 말한다. 방금 소진된 몇 건과 일주일째 열려 있는 한 건은 위험이 다르다 —
 * {@code outbox.pending.oldest.age}·{@code payment.unknown.oldest.age}·{@code compensation.pending.oldest.age}
 * 와 같은 판단이다. 나이는 <b>FAILED 로 마감된 시각({@code processedAt})</b>부터 잰다.
 *
 * <p>게이지 supplier는 스크레이프 스레드에서 매 수집마다 호출되므로 단일 집계 쿼리만 수행한다.
 */
@Component
public class WebhookMetrics {

    public WebhookMetrics(MeterRegistry meterRegistry, WebhookEventRepository repository) {
        Gauge.builder("payment.webhook.failed.count", this,
                        m -> (double) repository.countByStatus(WebhookEventStatus.FAILED))
                .description("자동 재처리가 없어 운영이 수동 대응해야 하는 FAILED 웹훅 행 수")
                .register(meterRegistry);

        Gauge.builder("payment.webhook.failed.oldest.age", this,
                        m -> repository.findOldestProcessedAt(WebhookEventStatus.FAILED)
                                .map(t -> (double) Math.max(0L, Duration.between(t, Instant.now()).getSeconds()))
                                .orElse(0.0))
                .baseUnit("seconds")
                .description("가장 오래된 FAILED 웹훅의 실패 후 경과 시간(초). 없으면 0")
                .register(meterRegistry);
    }
}
