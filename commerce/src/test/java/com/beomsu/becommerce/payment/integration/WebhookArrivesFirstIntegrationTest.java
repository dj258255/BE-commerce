package com.beomsu.becommerce.payment.integration;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.beomsu.becommerce.payment.webhook.WebhookSignatureVerifier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 웹훅이 승인 응답보다 <b>먼저</b> 도착하는 순서 역전을 실 MySQL에서 재현한다.
 *
 * <p><b>왜 단위 테스트로는 부족한가</b>: 기존 테스트는 {@code PaymentRecoveryService} 를 목으로 두고
 * {@code PAYMENT_NOT_FOUND} 를 던지게 만들어 <b>경로만</b> 고정했다. 실제로 문제가 되는 것은
 * "결제 행이 아직 커밋되지 않아 다른 트랜잭션에서 안 보이는" 상태인데, 그건 목으로 만들 수 없다.
 * 여기서는 결제 행이 <b>정말로 없는</b> 상태에 웹훅을 넣고, 그 뒤에 행이 생기면 닫히는지까지 본다.
 *
 * <p><b>실 PG 는 아니다</b>: 토스가 웹훅을 우리 서버로 쏘게 하려면 공개 URL 을 상점 관리자에
 * 등록해야 하고, 그래도 <b>언제 쏠지는 우리가 정하지 못한다.</b> 그래서 발신자는 우리가 대신하고
 * 서명·수신 경로·트랜잭션 경계는 실제 그대로 태운다. 이 차이는 한계로 남긴다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 이 클래스는 app.webhook.pending-retry.enabled=true 로 스케줄러를 켠다.
// 컨텍스트가 캐시된 채 남으면 컨테이너가 내려간 뒤에도 5초마다 죽은 DB 를 두드려
// Hikari 풀 타임아웃을 쏟아낸다(CI 에서 실제로 그렇게 됐다 — #177). 클래스가 끝나면 닫는다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("순서 역전 — 웹훅이 결제 행보다 먼저 와도 유실되지 않는다")
class WebhookArrivesFirstIntegrationTest {

    private static final String SECRET = "local-webhook-secret-please-override-32b";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "WebhookArrivesFirst");
        registry.add("payment.webhook.secret", () -> SECRET);
        // 이 테스트의 대상 — 보류 재처리 스케줄러를 켠다. 간격을 줄여 기다리는 시간을 짧게 한다.
        registry.add("app.webhook.pending-retry.enabled", () -> "true");
        registry.add("app.webhook.pending-interval-ms", () -> "1000");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    private ResponseEntity<String> postWebhook(String eventId, String paymentKey) {
        String body = "{\"eventId\":\"" + eventId + "\",\"eventType\":\"PAYMENT_STATUS_CHANGED\","
                + "\"data\":{\"paymentKey\":\"" + paymentKey + "\",\"status\":\"DONE\"}}";
        long ts = Instant.now().getEpochSecond();
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Signature", "t=" + ts + ",v1=" + WebhookSignatureVerifier.sign(SECRET, ts, body));
        return http.postForEntity("http://localhost:" + port + "/api/v1/webhooks/pg",
                new HttpEntity<>(body, h), String.class);
    }

    private String statusOf(String eventId) {
        return jdbc.queryForObject(
                "select status from webhook_events where external_event_id = ?", String.class, eventId);
    }

    @Test
    @DisplayName("결제 행이 없는 상태에서 웹훅을 받으면 200을 주고 PENDING_PAYMENT로 남는다")
    void webhookBeforePaymentRowIsHeldNotLost() {
        String eventId = "evt-race-" + System.nanoTime();
        String paymentKey = "pk-race-" + System.nanoTime();

        // PG 관점에서는 승인이 났고 웹훅을 쐈다. 우리 쪽 결제 행은 아직 없다.
        ResponseEntity<String> res = postWebhook(eventId, paymentKey);

        // PG에는 즉시 200을 준다 — 재전송을 유발하지 않는다.
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        // 비동기 해석이 끝나면 실패가 아니라 보류로 남아 있어야 한다.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(statusOf(eventId)).isEqualTo("PENDING_PAYMENT"));

        // 재시도 흔적이 남는다 — 스케줄러가 실제로 다시 집었다는 증거.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            Map<String, Object> row = jdbc.queryForMap(
                    "select retry_count, next_retry_at from webhook_events where external_event_id = ?",
                    eventId);
            assertThat(((Number) row.get("retry_count")).intValue()).isGreaterThanOrEqualTo(2);
            assertThat(row.get("next_retry_at")).isNotNull();
        });

        // 그리고 유실되지 않았다 — 원본 페이로드가 그대로 남아 있다.
        String raw = jdbc.queryForObject(
                "select raw_payload from webhook_events where external_event_id = ?", String.class, eventId);
        assertThat(raw).contains(paymentKey);
    }
}
