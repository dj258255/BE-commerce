package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * {@code KAFKA} 전달 — <b>이 저장소의 첫 인앱 컨슈머</b>다. 여태 {@code @KafkaListener}는 별도
 * 프로젝트({@code consumer-app/})에만 있었다. 그 판단과 대가는 ADR-034에 적었다.
 *
 * <p><b>게이트가 둘인 이유</b>: {@code @Profile("kafka")}는 브로커가 있을 때만, 프로퍼티는 전달
 * 방식이 <b>KAFKA 이거나 CDC</b>일 때만 켠다 — 둘은 <b>누가 토픽에 넣는가</b>만 다르고(앱의 아웃박스냐
 * binlog 냐) <b>소비는 같다</b>. KAFKA 만 열어 두면 CDC 모드에서 컨슈머 빈이 아예 안 만들어져
 * 토픽에 메시지가 쌓여도 <b>반영률이 0 이 된다</b>(실제로 그렇게 측정됐다). 프로파일이 없으면 외부화 자체가 꺼져 있어 이벤트가 나가지 않으므로
 * 리스너가 떠도 할 일이 없다 — 대신 브로커 연결을 시도해 테스트를 깨뜨린다. 그래서 둘 다 필요하다.
 *
 * <p><b>직렬화</b>: 여기서는 {@code JsonDeserializer}를 쓰지 않는다. 프로듀서가
 * {@code ByteArraySerializer}로 <b>이미 JSON 바이트</b>를 싣고, 타입 헤더({@code __TypeId__})에는
 * 발행자 클래스명이 들어 있다. String으로 받아 <b>이 앱의 ObjectMapper로 직접 바인딩</b>한다 —
 * 이 앱은 {@link UserActivityEvent}를 소유하므로(별도 프로젝트인 consumer-app과 다른 점) 리플렉션
 * 없이 레코드로 바로 읽을 수 있다.
 *
 * <p><b>멱등·순서</b>: {@link ContextStore#apply}가 seq로 판정한다. 재배달된 같은 이벤트는 무시된다.
 * 파티션 키가 {@code userId}라 사용자별 순서가 보존되므로 별도 락이 필요 없다.
 *
 * <p>파싱 실패는 <b>삼키지 않고 던진다</b> — 기본 에러 핸들러가 재시도하고, 끝내 실패하면 그대로
 * 올라간다. DLT 경로는 아직 만들지 않았다(리포트의 "남은 것").
 */
@Component
@Profile("kafka")
@ConditionalOnExpression(
        "'${app.personalization.transport:KAFKA}' == 'KAFKA' or '${app.personalization.transport:KAFKA}' == 'CDC'")
class KafkaContextTransport {

    /** 토픽명은 {@link UserActivityEvent}의 {@code @Externalized}와 같은 문자열이어야 한다. */
    static final String TOPIC = "user.activity";

    private final ContextApplier applier;
    private final NearlineDelay delay;
    private final ObjectMapper objectMapper;

    KafkaContextTransport(ContextApplier applier, NearlineDelay delay, ObjectMapper objectMapper) {
        this.applier = applier;
        this.delay = delay;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC,
            groupId = "${app.personalization.consumer.group-id:be-commerce-personalization-context}")
    void on(ConsumerRecord<String, String> record) throws Exception {
        UserActivityEvent event = objectMapper.readValue(record.value(), UserActivityEvent.class);
        delay.apply();
        applier.apply(event);
    }
}
