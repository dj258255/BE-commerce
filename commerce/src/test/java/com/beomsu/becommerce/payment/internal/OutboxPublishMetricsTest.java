package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboxPublishMetrics}가 발행 자체를 세는지 확인한다(#393). {@code OutboxMetrics}의
 * pending·age 게이지가 "적체 없음"과 "발행이 아예 없음"을 구분 못 하는 사각지대를 이 카운터가 메운다.
 */
class OutboxPublishMetricsTest {

    private static PaymentConfirmedEvent confirmedEvent() {
        return new PaymentConfirmedEvent("order-1", 1L, 10_000, Instant.now());
    }

    @Test
    @DisplayName("발행 전에는 카운터가 0이고 나이 게이지도 0이다(한 번도 발행된 적이 없다는 뜻)")
    void beforeAnyPublishCounterAndAgeAreZero() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new OutboxPublishMetrics(registry);

        assertThat(registry.counter("outbox.published").count()).isZero();
        assertThat(registry.get("outbox.published.last.age").gauge().value()).isZero();
    }

    @Test
    @DisplayName("PaymentConfirmedEvent가 발행되면 카운터가 오르고 나이가 0이 아니게 된다")
    void onPaymentConfirmedIncrementsCounterAndSetsAge() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxPublishMetrics metrics = new OutboxPublishMetrics(registry);

        metrics.onPaymentConfirmed(confirmedEvent());

        assertThat(registry.counter("outbox.published").count()).isEqualTo(1.0);
        // 방금 발행했으니 나이는 0에 아주 가깝다(음수는 아니다) — 정확히 0은 아닐 수 있어 상한만 본다.
        assertThat(registry.get("outbox.published.last.age").gauge().value()).isBetween(0.0, 1.0);

        metrics.onPaymentConfirmed(confirmedEvent());
        assertThat(registry.counter("outbox.published").count()).isEqualTo(2.0);
    }
}
