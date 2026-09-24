package com.beomsu.becommerce.payment.integration;

import com.beomsu.becommerce.testsupport.SharedContainers;
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

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * <b>토스가 실제로 보내는 모양</b>의 웹훅으로 순서 역전을 재현한다.
 *
 * <p><b>기존 재현 테스트와 무엇이 다른가</b>: {@code WebhookArrivesFirstIntegrationTest} 는 우리
 * 자체 규약(HMAC {@code X-Signature})으로 쏜다. 그런데 실 PG를 붙여 보니 <b>토스는 그 헤더를 보내지
 * 않고 {@code eventId} 도 주지 않는다.</b> 즉 기존 수신부로는 실 웹훅이 들어올 수 없었다. 이 테스트는
 * 그 간극을 메운 {@code /api/v1/webhooks/toss} 경로를 토스의 실제 페이로드 모양으로 태운다.
 *
 * <p><b>페이로드의 출처</b>: {@code paymentKey} 는 토스 테스트 API로 실제 가상계좌를 발급받아
 * ({@code POST /v1/virtual-accounts}) 돌려받은 값이다. 카드 정보 없이 시크릿 키만으로 발급되므로
 * 실 결제 수단을 태우지 않고도 실 PG가 발급한 식별자를 쓸 수 있다.
 *
 * <p><b>여기서 재현되지 않는 것</b>: 토스가 <b>직접</b> 우리 서버로 쏘는 마지막 한 걸음은 공개 URL을
 * 상점 관리자에 등록해야 하고, 그건 사람이 콘솔에서 눌러야 한다. 그래서 발신자만 우리가 대신하고
 * 수신 경로·정규화·트랜잭션 경계는 실제 그대로 태운다.
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// 이 클래스는 app.webhook.pending-retry.enabled=true 로 스케줄러를 켠다.
// 컨텍스트가 캐시된 채 남으면 컨테이너가 내려간 뒤에도 5초마다 죽은 DB 를 두드려
// Hikari 풀 타임아웃을 쏟아낸다(CI 에서 실제로 그렇게 됐다 — #177). 클래스가 끝나면 닫는다.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@DisplayName("순서 역전 — 토스 모양 웹훅이 결제 행보다 먼저 와도 유실되지 않는다")
class TossWebhookArrivesFirstIntegrationTest {

    /** 토스 테스트 API가 실제로 발급한 가상계좌 결제의 paymentKey. */
    private static final String REAL_TOSS_PAYMENT_KEY = "tviva20260905075658KhxD3";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "TossWebhookArrivesFirst");
        registry.add("payment.webhook.secret", () -> "local-webhook-secret-please-override-32b");
        registry.add("app.webhook.pending-retry.enabled", () -> "true");
        registry.add("app.webhook.pending-interval-ms", () -> "1000");
    }

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    /** 토스가 보내는 그대로 — 서명 헤더도 eventId도 없다. */
    private ResponseEntity<String> postTossWebhook(String paymentKey, String sentAt) {
        String body = """
                {"eventType":"PAYMENT_STATUS_CHANGED","createdAt":"%s",
                 "data":{"paymentKey":"%s","orderId":"pay-order-1788562617",
                 "status":"DONE","method":"가상계좌","totalAmount":15000}}"""
                .formatted(sentAt, paymentKey);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return http.postForEntity("http://localhost:" + port + "/api/v1/webhooks/toss",
                new HttpEntity<>(body, h), String.class);
    }

    private String eventIdOf(String paymentKey) {
        return "toss:" + paymentKey + ":DONE";
    }

    @Test
    @DisplayName("서명 헤더 없이 와도 받아서 보류로 남기고, 재전송은 한 건으로 접힌다")
    void tossShapedWebhookBeforePaymentRowIsHeldNotLost() {
        String paymentKey = REAL_TOSS_PAYMENT_KEY;
        String eventId = eventIdOf(paymentKey);
        jdbc.update("delete from webhook_events where external_event_id = ?", eventId);

        // 토스 관점에서는 입금이 확인돼 상태가 바뀌었다. 우리 쪽 결제 행은 아직 없다.
        ResponseEntity<String> res = postTossWebhook(paymentKey, "2026-09-05T16:56:58+09:00");

        // 토스에는 즉시 200 — 못 주면 같은 이벤트를 계속 다시 보낸다.
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        // 실패가 아니라 보류다.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select status from webhook_events where external_event_id = ?",
                        String.class, eventId)).isEqualTo("PENDING_PAYMENT"));

        // 스케줄러가 실제로 다시 집었다.
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject(
                        "select retry_count from webhook_events where external_event_id = ?",
                        Integer.class, eventId)).isGreaterThanOrEqualTo(2));

        // 토스가 같은 이벤트를 다시 보내도 한 건이다 — eventId를 내용에서 만들었기 때문.
        postTossWebhook(paymentKey, "2026-09-05T17:10:02+09:00");
        assertThat(jdbc.queryForObject(
                "select count(*) from webhook_events where external_event_id = ?",
                Integer.class, eventId)).isEqualTo(1);

        // 원본은 지워지지 않았다 — 토스가 보낸 필드가 그대로 남는다.
        String raw = jdbc.queryForObject(
                "select raw_payload from webhook_events where external_event_id = ?", String.class, eventId);
        assertThat(raw).contains(paymentKey).contains("가상계좌").contains("pay-order-1788562617");
    }
}
