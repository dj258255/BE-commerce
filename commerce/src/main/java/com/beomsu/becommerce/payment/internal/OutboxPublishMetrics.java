package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 아웃박스로 나가는 대표 이벤트({@link PaymentConfirmedEvent}) 발행 자체를 센다(#393).
 *
 * <p><b>왜 DB 게이지만으로는 안 되는가</b>: {@code OutboxMetrics}의 {@code pending.count}·
 * {@code pending.oldest.age}는 {@code event_publication} 테이블을 스크레이프마다 읽는다. 그런데
 * 발행이 애초에 일어나지 않으면(리스너 등록이 빠져 Modulith가 그 이벤트를 지속 대상으로 보지 않는
 * 경우 등) 그 테이블은 처음부터 끝까지 비어 있어 <b>pending도 0, oldest age도 0</b>을 낸다 —
 * "적체 없음"과 "발행 자체가 멈춤"이 같은 값으로 보인다({@code OutboxMetrics} 참고).
 *
 * <p>{@code PersonalizationMetrics#contextApplied}가 같은 문제를(활동 반영이 멈춰도 나이 게이지가
 * 0을 내는 것) DB가 아니라 애플리케이션 코드에서 직접 세어 풀었던 것과 같은 이유로, 여기서도 순수
 * {@code @EventListener}로 발행을 직접 센다. Modulith의 지속(persist) 판정과 독립적으로, 이벤트가
 * 발행되면(성공 확정 경로 어디에서든) 항상 불린다.
 *
 * <p>{@link PaymentConfirmedEvent}를 고른 이유: 정산·원장·알림·에스크로가 걸린 대표 아웃박스
 * 이벤트라(V24 마이그레이션, 리스너 5개) 이게 멈추면 결제는 되는데 후속 처리가 전혀 안 도는
 * 상태다. 발행 지점이 셋(정상 확정·복구 조회 확정 둘)이라 개별 호출부마다 세는 대신
 * 이벤트 타입으로 한 곳에서 센다 — 새 발행 경로가 생겨도 놓치지 않는다.
 */
@Component
class OutboxPublishMetrics {

    private final Counter published;

    /** 0 = 아직 한 번도 발행된 적이 없다(게이지가 그 구간에서 0을 내보낸다). */
    private final AtomicLong lastPublishedAtMillis = new AtomicLong(0);

    OutboxPublishMetrics(MeterRegistry meterRegistry) {
        this.published = Counter.builder("outbox.published")
                .description("아웃박스로 나가는 대표 이벤트(PaymentConfirmedEvent) 발행 수. "
                        + "결제 확정 트래픽은 있는데 이 값이 0으로 머물면 발행 경로가 죽은 것이다(#393)")
                .register(meterRegistry);

        Gauge.builder("outbox.published.last.age", lastPublishedAtMillis,
                        OutboxPublishMetrics::ageSeconds)
                .description("마지막 발행 이후 경과(초). 한 번도 발행된 적이 없으면 0을 내보낸다"
                        + "(PersonalizationMetrics#contextApplied와 같은 규칙)")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    @EventListener
    void onPaymentConfirmed(PaymentConfirmedEvent event) {
        published.increment();
        lastPublishedAtMillis.set(System.currentTimeMillis());
    }

    private static double ageSeconds(AtomicLong lastPublished) {
        long last = lastPublished.get();
        if (last == 0) {
            return 0;
        }
        return (System.currentTimeMillis() - last) / 1000.0;
    }
}
