package com.beomsu.becommerce.escrow;

import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.modulith.events.Externalized;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 에스크로 이벤트 Kafka 외부화 <b>라우팅 설정</b> 단위 테스트 — 결제의
 * {@code EventExternalizationTest}와 같은 패턴(브로커·컨텍스트 없이 애노테이션 메타만 검사).
 *
 * <p>정산이 별도 프로세스로 분리되면 구매확정 신호({@link EscrowReleasedEvent})가 브로커를
 * 타야 하므로, {@code @Externalized}가 붙어 있고 라우팅 키가 {@code orderNo}(파티션 키)인지
 * 고정한다 — 같은 주문의 이벤트가 파티션 안에서 순서 보존되는 계약의 회귀 방지.
 */
class EscrowEventExternalizationTest {

    @Test
    void escrowReleasedEventIsExternalizedRoutedByOrderNo() {
        Externalized annotation = AnnotatedElementUtils.findMergedAnnotation(
                EscrowReleasedEvent.class, Externalized.class);

        assertThat(annotation)
                .as("EscrowReleasedEvent must be @Externalized for the out-of-process settlement service")
                .isNotNull();

        String[] parts = annotation.value().split("::", 2);
        assertThat(parts[0]).isEqualTo("escrow.released");
        // orderNo를 파티션 키로 써 같은 주문의 이벤트가 같은 파티션에서 순서대로 흐르게 한다.
        assertThat(parts[1]).isEqualTo("#{orderNo}");
    }
}
