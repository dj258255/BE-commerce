package com.beomsu.becommerceconsumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 결제 이벤트 Kafka 리스너 (정산 알림 데모).
 *
 * <p><b>at-least-once</b>: 메인 앱의 Outbox(event_publication)가 발행을 보장하는 대신
 * 재발행·재시도로 <b>같은 이벤트가 중복 수신될 수 있다</b>. 실소비자는 orderNo/paymentId 기반
 * 멱등 처리(processed_events 같은 소비 이력)가 필수다 — 여기서는 로그 데모라 카운트만 한다.
 *
 * <p>이벤트는 Zero-Payload 지향(식별자 + 최소 정보만)이다. 상세가 필요한 실소비자는
 * 페이로드를 신뢰 원천으로 삼지 말고 orderNo로 조회 API를 되읽어 최신 상태를 확정하는
 * 패턴을 쓴다(순서 역전·스키마 결합 회피).
 */
@Component
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    // 웹 없는 경량 워커라 Jackson 자동구성(ObjectMapper 빈)이 없다(Jackson2ObjectMapperBuilder가
    // spring-web 소속) → 직접 생성한다. readTree만 쓰므로 커스터마이징 불필요.
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicLong confirmedCount = new AtomicLong();
    private final AtomicLong canceledCount = new AtomicLong();

    @KafkaListener(topics = "payment.confirmed")
    public void onConfirmed(ConsumerRecord<String, String> rec) {
        JsonNode json = parse(rec);
        long total = confirmedCount.incrementAndGet();
        log.info("[정산알림] 결제 완료 수신 orderNo={} amount={} partition={} offset={} (누적 {}건)",
                json.path("orderNo").asText(), json.path("amount").asLong(),
                rec.partition(), rec.offset(), total);
    }

    @KafkaListener(topics = "payment.canceled")
    public void onCanceled(ConsumerRecord<String, String> rec) {
        JsonNode json = parse(rec);
        long total = canceledCount.incrementAndGet();
        log.info("[정산알림] 결제 취소 수신 orderNo={} cancelAmount={} fullyCanceled={} partition={} offset={} (누적 {}건)",
                json.path("orderNo").asText(), json.path("cancelAmount").asLong(),
                json.path("fullyCanceled").asBoolean(),
                rec.partition(), rec.offset(), total);
    }

    /**
     * 파싱 실패는 <b>던진다</b>. {@link DeadLetterConfig}의 에러 핸들러가 재시도 후 DLT로 격리한다.
     *
     * <p>예전에는 여기서 warn 찍고 {@code null}을 반환해 skip했다. 파티션이 멈추는 걸 막으려던
     * 것이었는데, 그러면 <b>그 이벤트가 영영 사라진다.</b> 오프셋까지 커밋되니 되돌릴 방법도 없다.
     * "멈추지 않게 하는 것"과 "버리는 것"은 다른 일이다.
     */
    private JsonNode parse(ConsumerRecord<String, String> rec) {
        try {
            return objectMapper.readTree(rec.value());
        } catch (Exception e) {
            throw new PoisonMessageException(
                    "페이로드를 JSON으로 읽지 못했습니다. topic=%s partition=%d offset=%d"
                            .formatted(rec.topic(), rec.partition(), rec.offset()), e);
        }
    }

    /** 재시도해도 같은 결과인 실패. 에러 핸들러가 DLT로 보낸다. */
    static class PoisonMessageException extends RuntimeException {
        PoisonMessageException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    long confirmedCount() {
        return confirmedCount.get();
    }

    long canceledCount() {
        return canceledCount.get();
    }
}
