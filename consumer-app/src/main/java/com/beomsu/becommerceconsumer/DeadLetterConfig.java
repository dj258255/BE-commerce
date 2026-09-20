package com.beomsu.becommerceconsumer;

import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 처리하지 못한 메시지를 <b>버리지 않고 격리</b>한다.
 *
 * <p><b>예전에는 왜 버렸나</b>: 파싱 실패에 예외를 던지면 컨테이너가 같은 오프셋을 무한 재시도해
 * <b>파티션 소비 전체가 멈춘다.</b> 그게 싫어서 warn 찍고 skip했다. 막으려던 문제는 맞았는데,
 * <b>선택지를 둘로만 봤다</b> — 던지거나(멈춤) 버리거나(유실). 그 사이에 이 자리가 있다.
 *
 * <p>본체(pay)는 이미 알림 실패를 {@code DeadLetter} 테이블로 격리하고 어드민에서 재처리한다.
 * 같은 프로젝트 안에서 한쪽은 격리하고 한쪽은 버리고 있었다.
 *
 * <p><b>무엇이 남는가</b>: 원본 payload와 함께 {@code kafka_dlt-*} 헤더로 원 토픽·파티션·오프셋·
 * 예외 메시지가 실린다. 그래야 나중에 무엇이 왜 실패했는지 보고 되돌릴 수 있다.
 *
 * <p><b>재시도는 짧게</b>: 파싱 실패는 다시 시도해도 같은 결과다. 일시적 장애(브로커 순단 등)만
 * 건지도록 2회까지만 재시도하고 바로 DLT로 보낸다.
 */
@Configuration
class DeadLetterConfig {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterConfig.class);

    static final String CONFIRMED_DLT = "payment.confirmed.DLT";
    static final String CANCELED_DLT = "payment.canceled.DLT";

    @Bean
    NewTopic confirmedDlt() {
        return TopicBuilder.name(CONFIRMED_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    NewTopic canceledDlt() {
        return TopicBuilder.name(CANCELED_DLT).partitions(1).replicas(1).build();
    }

    @Bean
    DefaultErrorHandler errorHandler(KafkaTemplate<String, String> template) {
        var recoverer = new DeadLetterPublishingRecoverer(template);
        // 2회 재시도 후 DLT. 파싱 실패는 재시도로 안 풀리고, 일시적 장애만 건진다.
        var handler = new DefaultErrorHandler(recoverer, new FixedBackOff(500L, 2L));
        handler.setRetryListeners((rec, ex, attempt) ->
                log.warn("[정산알림] 처리 실패 재시도 {}회 topic={} partition={} offset={} : {}",
                        attempt, rec.topic(), rec.partition(), rec.offset(), ex.toString()));
        return handler;
    }
}
