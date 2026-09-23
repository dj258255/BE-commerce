package com.beomsu.becommerce.payment;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이벤트 스키마 진화 — 필드 추가는 소비자 배포 없이 안전하고, 삭제는 배포 순서를 강제한다(ADR-026).
 *
 * <p>외부화된 이벤트는 Kafka 로 나가 소비자(정산 서비스 등)가 붙는다. 발행자와 소비자는 <b>다른
 * 시점에 배포</b>되므로, 두 버전이 동시에 존재하는 구간이 반드시 생긴다. 그 구간에서 무엇이
 * 안전하고 무엇이 위험한지를 여기서 고정한다.
 *
 * <p>{@link V1}·{@link V2} 는 실제 계약({@code payment/PaymentConfirmedEvent})의 두 버전을 흉내낸
 * 것이다. 필드 추가가 안전한 이유는 <b>Jackson 설정 하나</b>({@code FAIL_ON_UNKNOWN_PROPERTIES}
 * 비활성)에 달려 있다 — 그래서 그 설정도 같이 못 박는다.
 */
class EventSchemaEvolutionTest {

    /** 지금의 계약. */
    record V1(String orderNo, long paymentId, long amount, Instant approvedAt) {
    }

    /** 필드 하나를 더한 다음 버전(호환되는 변경). */
    record V2(String orderNo, long paymentId, long amount, Instant approvedAt, String currency) {
    }

    // Spring Boot 가 쓰는 것과 같은 방식으로 만든다(Jackson2ObjectMapperBuilder). Boot 는
    // FAIL_ON_UNKNOWN_PROPERTIES 를 기본 비활성으로 둔다 — 이벤트 외부화도 이 매퍼를 탄다.
    private final ObjectMapper mapper = Jackson2ObjectMapperBuilder.json()
            .modules(new JavaTimeModule()).build();

    @Test
    @DisplayName("필드 추가: 새 페이로드를 옛 소비자가 읽어도 깨지지 않는다(모르는 필드는 무시)")
    void addedFieldIsIgnoredByOldConsumer() throws Exception {
        String newPayload = mapper.writeValueAsString(
                new V2("o-1", 1L, 10_000, Instant.parse("2026-09-19T07:00:00Z"), "KRW"));

        V1 old = mapper.readValue(newPayload, V1.class);

        assertThat(old.orderNo()).isEqualTo("o-1");
        assertThat(old.amount()).isEqualTo(10_000);
    }

    @Test
    @DisplayName("필드 추가: 옛 페이로드를 새 소비자가 읽으면 새 필드가 null 이다(크래시 없음)")
    void missingFieldIsNullOnNewConsumer() throws Exception {
        String oldPayload = mapper.writeValueAsString(
                new V1("o-1", 1L, 10_000, Instant.parse("2026-09-19T07:00:00Z")));

        V2 next = mapper.readValue(oldPayload, V2.class);

        assertThat(next.currency()).isNull();
        assertThat(next.orderNo()).isEqualTo("o-1");
    }

    @Test
    @DisplayName("이 호환은 Jackson 설정 하나에 달려 있다 — FAIL_ON_UNKNOWN_PROPERTIES 가 꺼져 있어야 한다")
    void additionCompatibilityRestsOnJacksonConfig() {
        assertThat(mapper.isEnabled(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES))
                .as("켜면 필드 추가가 옛 소비자를 예외로 깨뜨린다 — 이벤트 추가가 더 이상 안전하지 않다")
                .isFalse();
    }

    @Test
    @DisplayName("필드 삭제: 옛 소비자의 기본형 필드는 조용히 0 이 된다 — 예외가 아니다")
    void removedPrimitiveFieldSilentlyBecomesZero() throws Exception {
        // 발행자가 amount 를 더 이상 보내지 않는다. 소비자는 아직 amount 를 읽는다.
        String payloadWithoutAmount = "{\"orderNo\":\"o-1\",\"paymentId\":1,"
                + "\"approvedAt\":\"2026-09-19T07:00:00Z\"}";

        V1 old = mapper.readValue(payloadWithoutAmount, V1.class);

        // 예외가 아니라 0 이다. 스키마 검사로도 안 잡히고, 소비자는 0원으로 조용히 처리한다.
        // 이것이 "삭제는 소비자 먼저"가 강제되는 이유다.
        assertThat(old.amount()).isZero();
    }
}
