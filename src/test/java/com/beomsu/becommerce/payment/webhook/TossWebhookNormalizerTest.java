package com.beomsu.becommerce.payment.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 토스 페이로드 정규화 — 멱등 키를 <b>내용에서</b> 만들고, 원본은 지우지 않는다.
 */
@DisplayName("토스 웹훅 정규화")
class TossWebhookNormalizerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TossWebhookNormalizer normalizer = new TossWebhookNormalizer(mapper);

    @Test
    @DisplayName("결제 상태 변경 — data.paymentKey와 status로 eventId를 만든다")
    void buildsEventIdFromPaymentKeyAndStatus() throws Exception {
        String toss = """
                {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2026-09-05T16:56:58+09:00",
                 "data":{"paymentKey":"tviva20260905075658KhxD3","orderId":"pay-order-1","status":"DONE"}}""";

        var out = mapper.readTree(normalizer.normalize(toss));

        assertThat(out.get("eventId").asText()).isEqualTo("toss:tviva20260905075658KhxD3:DONE");
        assertThat(out.get("eventType").asText()).isEqualTo("PAYMENT_STATUS_CHANGED");
        // 원본은 그대로 남는다 — 나중에 무슨 일이 있었는지 되짚을 수 있어야 한다.
        assertThat(out.get("data").get("orderId").asText()).isEqualTo("pay-order-1");
        assertThat(out.get("createdAt").asText()).isEqualTo("2026-09-05T16:56:58+09:00");
    }

    @Test
    @DisplayName("재전송은 같은 eventId로 접힌다 — 수신 시각이 달라도 값이 같다")
    void resendCollapsesToSameEventId() throws Exception {
        String first = """
                {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2026-09-05T16:56:58+09:00",
                 "data":{"paymentKey":"tviva1","status":"DONE"}}""";
        String resent = """
                {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"2026-09-05T17:10:02+09:00",
                 "data":{"paymentKey":"tviva1","status":"DONE"}}""";

        assertThat(mapper.readTree(normalizer.normalize(first)).get("eventId").asText())
                .isEqualTo(mapper.readTree(normalizer.normalize(resent)).get("eventId").asText());
    }

    @Test
    @DisplayName("입금 콜백 — eventType이 없으면 붙이고 transactionKey로 eventId를 만든다")
    void infersDepositCallback() throws Exception {
        String toss = """
                {"createdAt":"2026-09-05T16:56:58+09:00","secret":"ps_ma60R",
                 "status":"DONE","transactionKey":"tx-abc","orderId":"pay-order-1"}""";

        var out = mapper.readTree(normalizer.normalize(toss));

        assertThat(out.get("eventType").asText()).isEqualTo("DEPOSIT_CALLBACK");
        assertThat(out.get("eventId").asText()).isEqualTo("toss:tx-abc:DONE");
    }

    @Test
    @DisplayName("식별자가 없으면 조용히 넘기지 않고 던진다 — 키 없이 저장하면 중복 처리된다")
    void rejectsPayloadWithoutIdentifier() {
        assertThatThrownBy(() -> normalizer.normalize("{\"eventType\":\"PAYMENT_STATUS_CHANGED\"}"))
                .isInstanceOf(WebhookException.class)
                .hasMessageContaining("멱등 키");
    }

    @Test
    @DisplayName("JSON 객체가 아니면 던진다")
    void rejectsNonObjectBody() {
        assertThatThrownBy(() -> normalizer.normalize("[]"))
                .isInstanceOf(WebhookException.class);
    }
}
