package com.beomsu.becommerceconsumer;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 리스너 파싱 로직 단위 테스트 — 브로커 없이 ConsumerRecord를 직접 넣어 검증한다.
 */
class PaymentEventListenerTest {

    private final PaymentEventListener listener = new PaymentEventListener();

    @Test
    @DisplayName("payment.confirmed JSON을 파싱해 카운트를 올린다")
    void onConfirmed_parsesAndCounts() {
        String value = "{\"orderNo\":\"ORD-1\",\"paymentId\":10,\"amount\":15000,\"approvedAt\":\"2026-07-06T00:00:00Z\"}";
        listener.onConfirmed(new ConsumerRecord<>("payment.confirmed", 0, 0L, "ORD-1", value));

        assertThat(listener.confirmedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("payment.canceled JSON을 파싱해 카운트를 올린다")
    void onCanceled_parsesAndCounts() {
        String value = "{\"orderNo\":\"ORD-1\",\"paymentId\":10,\"cancelAmount\":5000,\"fullyCanceled\":false}";
        listener.onCanceled(new ConsumerRecord<>("payment.canceled", 0, 0L, "ORD-1", value));

        assertThat(listener.canceledCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("포이즌 메시지(JSON 아님)는 던져서 DLT로 보낸다 — 조용히 버리지 않는다")
    void poisonMessage_isThrownForDeadLetter() {
        var poison = new ConsumerRecord<>("payment.confirmed", 0, 1L, "ORD-2", "not-json{{{");

        // 예전에는 warn 만 남기고 skip 했다. 그러면 오프셋까지 커밋돼 <메시지가 사라진다>.
        // 본체는 이미 알림 실패를 DeadLetter 로 격리하고 어드민에서 재처리하는데,
        // 소비자만 조용히 버리고 있었다. 던지면 에러 핸들러가 2회 재시도 후 DLT 로 보낸다.
        assertThatThrownBy(() -> listener.onConfirmed(poison))
                .isInstanceOf(PaymentEventListener.PoisonMessageException.class)
                .hasMessageContaining("payment.confirmed")
                .hasMessageContaining("offset=1");

        assertThat(listener.confirmedCount()).isZero();
    }
}
