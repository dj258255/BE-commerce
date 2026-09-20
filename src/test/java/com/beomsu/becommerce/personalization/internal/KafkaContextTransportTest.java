package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 와이어 파싱을 브로커 없이 검증한다 — {@code consumer-app}의 {@code PaymentEventListenerTest}와
 * 같은 방식이다(레코드를 직접 만들어 넣는다).
 *
 * <p>여기서 지키려는 것은 <b>payload에 결합되지 않는 것</b>과 <b>깨진 메시지를 삼키지 않는 것</b>이다.
 * 후자는 이 저장소가 실제로 겪은 실패다 — 파싱 실패를 warn으로 넘기면 그 이벤트가 영영 사라진다.
 */
class KafkaContextTransportTest {

    private ContextApplier applier;
    private ObjectMapper objectMapper;
    private KafkaContextTransport transport;

    @BeforeEach
    void setUp() {
        applier = mock(ContextApplier.class);
        objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 지연 0 — 테스트가 재우지 않는다.
        transport = new KafkaContextTransport(applier, new NearlineDelay(0), objectMapper);
    }

    private ConsumerRecord<String, String> record(String value) {
        return new ConsumerRecord<>("user.activity", 0, 0L, "1", value);
    }

    @Test
    @DisplayName("JSON 페이로드를 이벤트로 읽어 컨텍스트에 적용한다")
    void parsesAndApplies() throws Exception {
        UserActivityEvent event = new UserActivityEvent(1L, 42L, "CLICK", 7L,
                Instant.parse("2026-09-20T10:00:00Z"), "SYNTHETIC");
        String payload = objectMapper.writeValueAsString(event);

        transport.on(record(payload));

        verify(applier).apply(event);
    }

    @Test
    @DisplayName("깨진 페이로드는 던진다 — 삼키면 이벤트가 조용히 사라진다")
    void poisonMessageThrows() {
        assertThatThrownBy(() -> transport.on(record("{이건 JSON이 아니다")))
                .isInstanceOf(Exception.class);

        verify(applier, org.mockito.Mockito.never()).apply(any());
    }

    @Test
    @DisplayName("토픽명이 발행 애노테이션과 같은 문자열이다 — 둘이 갈라지면 소비가 조용히 멈춘다")
    void topicMatchesExternalizedAnnotation() {
        org.springframework.modulith.events.Externalized externalized =
                UserActivityEvent.class.getAnnotation(org.springframework.modulith.events.Externalized.class);

        org.assertj.core.api.Assertions.assertThat(externalized).isNotNull();
        org.assertj.core.api.Assertions.assertThat(externalized.value())
                .startsWith(KafkaContextTransport.TOPIC + "::");
    }
}
