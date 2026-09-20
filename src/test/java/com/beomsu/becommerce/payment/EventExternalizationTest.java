package com.beomsu.becommerce.payment;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.Externalized;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 이벤트 Kafka 외부화 <b>라우팅 설정</b> 단위 테스트.
 *
 * <p>브로커나 스프링 컨텍스트 없이 애노테이션 메타만 검사한다 — {@code @Externalized}가 두 이벤트에
 * 붙어 있고, 라우팅 키가 {@code orderNo}(파티션 키)로 잡혀 같은 주문의 이벤트 순서가 보존되는지 확인한다.
 * (실제 발행 경로는 {@code spring.modulith.events.externalization.enabled} + kafka 프로파일로만 켜진다.)
 */
class EventExternalizationTest {

    @Test
    void paymentConfirmedEventIsExternalizedRoutedByOrderNo() {
        assertRouting(PaymentConfirmedEvent.class, "payment.confirmed");
    }

    @Test
    void paymentCanceledEventIsExternalizedRoutedByOrderNo() {
        assertRouting(PaymentCanceledEvent.class, "payment.canceled");
    }

    private static void assertRouting(Class<?> eventType, String expectedTopic) {
        // AnnotatedElementUtils로 value/target 별칭(@AliasFor)을 병합해 읽는다.
        Externalized annotation =
                AnnotatedElementUtils.findMergedAnnotation(eventType, Externalized.class);

        assertThat(annotation)
                .as("%s must be @Externalized for out-of-process consumers", eventType.getSimpleName())
                .isNotNull();

        String routingTarget = annotation.value();
        assertThat(routingTarget).contains("::");

        String[] parts = routingTarget.split("::", 2);
        String topic = parts[0];
        String routingKey = parts[1];

        assertThat(topic).isEqualTo(expectedTopic);
        // orderNo를 파티션 키로 써 같은 주문의 이벤트가 같은 파티션에서 순서대로 흐르게 한다.
        assertThat(routingKey).isEqualTo("#{orderNo}");
    }
}
