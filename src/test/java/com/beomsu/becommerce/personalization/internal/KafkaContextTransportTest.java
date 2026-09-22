package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
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

    /**
     * 실제 CDC 커넥터가 낸 값을 그대로 옮긴 페이로드다 — Debezium 이 binlog 에서 만들어
     * {@code user.activity} 토픽에 넣은 문자열이고, 커넥터 설정은
     * {@code cdc/register-user-activity-connector.json} 이다. 값은 손대지 않았다.
     *
     * <p>{@code id}·{@code created_at} 은 이벤트({@link UserActivityEvent})에 없는 테이블 컬럼이고,
     * {@code occurredAt} 은 TimestampConverter SMT 가 만든 ISO-8601 문자열이다. 앞의 두 필드를
     * 컨슈머가 무시하는지를 <b>추정이 아니라 테스트로</b> 박는다.
     */
    @Test
    @DisplayName("CDC 페이로드를 읽는다 — 이벤트에 없는 여분 컬럼(id·created_at)이 섞여도 깨지지 않는다")
    void parsesCdcPayloadDespiteExtraColumns() throws Exception {
        String payload = "{\"id\":49264,\"userId\":999003,\"itemId\":77777,\"activityType\":\"CLICK\","
                + "\"seq\":5,\"source\":\"SYNTHETIC\",\"occurredAt\":\"2026-09-22T21:41:26.600Z\","
                + "\"created_at\":1790113286600090}";

        // 소비자의 ObjectMapper 는 Spring Boot 가 만드는 것이라 FAIL_ON_UNKNOWN_PROPERTIES 가 기본 off 다
        // (그래서 여분 필드를 무시한다). setUp 의 순수 ObjectMapper 는 그 설정이 아니라 여분 필드에서
        // UnrecognizedPropertyException 을 던진다 — 소비자와 같은 매퍼를 써서 실제 설정을 재현한다.
        ObjectMapper consumerMapper = Jackson2ObjectMapperBuilder.json().build();
        ContextApplier consumerApplier = mock(ContextApplier.class);
        KafkaContextTransport consumer =
                new KafkaContextTransport(consumerApplier, new NearlineDelay(0), consumerMapper);

        // 1) 여분 컬럼이 섞여 있어도 예외 없이 지나간다 — 이 테스트의 핵심
        consumer.on(record(payload));

        // 2) 넘어간 이벤트의 값이 맞다
        ArgumentCaptor<UserActivityEvent> captor = ArgumentCaptor.forClass(UserActivityEvent.class);
        verify(consumerApplier).apply(captor.capture());
        UserActivityEvent event = captor.getValue();

        assertThat(event.userId()).isEqualTo(999003L);
        assertThat(event.itemId()).isEqualTo(77777L);
        assertThat(event.activityType()).isEqualTo("CLICK");
        assertThat(event.seq()).isEqualTo(5L);
        assertThat(event.source()).isEqualTo("SYNTHETIC");
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-09-22T21:41:26.600Z"));
    }
}
