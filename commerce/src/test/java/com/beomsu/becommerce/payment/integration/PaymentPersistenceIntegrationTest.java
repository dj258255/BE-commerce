package com.beomsu.becommerce.payment.integration;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 결제 승인/취소/구매확정이 <b>실제 MySQL에 확정(persist)되는지</b>를 실 DB로 검증하는 통합 테스트.
 *
 * <p><b>왜 기본 스위트에서 제외하는가(@Tag("integration"))</b><br>
 * 이 테스트는 MySQL + Redis 컨테이너를 띄우고 그 위로 Spring 애플리케이션 전체를 부팅한다
 * (RANDOM_PORT). {@code @SpringBootTest}+Testcontainers라 무겁고, 일부 로컬 환경(예: 이 개발용
 * 맥)에서는 컨테이너 부팅이 hang한다. 따라서 {@code ./gradlew test}의 빠르고 결정적인 기본
 * 스위트에서는 {@code excludeTags 'integration'}으로 빠지고, Docker가 준비된 CI 환경에서
 * {@code ./gradlew integrationTest}로만 실행한다.
 *
 * <p><b>무엇을·왜 검증하는가 — 목 단위 테스트가 못 잡는 영속 계층 회귀</b><br>
 * 리포지토리를 목으로 둔 단위 테스트는 "로직"만 검증한다. 그래서 OSIV off 환경에서 서비스
 * 트랜잭션이 dirty-checking UPDATE를 flush하지 않아 <b>승인 API는 {@code PAID/DONE}을 응답하는데
 * DB에는 {@code PENDING_PAYMENT/IN_PROGRESS}로 남던</b> 회귀를 잡지 못했다(saveAndFlush로 수정).
 * 이 테스트는 HTTP <b>응답</b>이 아니라, {@link JdbcTemplate}로 테이블을 <b>직접 재조회</b>해
 * 상태가 실제로 DB에 확정됐는지 단언한다 — 목이 흉내 낼 수 없는 실 영속 계층을 실 MySQL로 지킨다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("결제 영속 통합 — 승인/취소/구매확정이 실 MySQL에 확정되는지(응답이 아니라 DB 상태)")
class PaymentPersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("becommerce")
            .withUsername("becommerce")
            .withPassword("becommerce");

    // Redis 컨테이너를 확실히 붙여 컨텍스트 부팅 리스크를 없앤다(Lettuce 지연 초기화에 기대지 않는다).
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        // Flyway V1~V5 마이그레이션이 이 실 MySQL 위로 돈다.
        String url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // Redis(멱등 선체크/분산락) — 컨테이너 주소를 주입해 부팅을 안정화한다.
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        // 이 테스트에서 쓰지 않는 Kafka 외부화는 확실히 꺼 둔다(브로커 없이 부팅).
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    // DataSource로 자동 구성된 JdbcTemplate — 리포지토리가 package-private이라 SQL로 직접 재조회한다.
    @Autowired
    JdbcTemplate jdbc;

    // --- 테스트 케이스: HTTP 응답이 아니라 DB에 확정된 상태를 단언한다 ---

    @Test
    @DisplayName("승인: 응답은 물론 DB에도 orders=PAID / payments=DONE / balance=10000 으로 확정된다")
    void confirm_persistsPaidAndDone() {
        String token = login("1", "user-local-only");
        String orderNo = createOrder(token, 1, 1);

        ResponseEntity<JsonNode> res = confirm(token, orderNo, 10_000);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        // 핵심: 응답이 아니라 DB 실제 상태. flush 누락 회귀라면 여기서 PENDING_PAYMENT/IN_PROGRESS로 잡힌다.
        assertThat(orderStatus(orderNo)).isEqualTo("PAID");
        assertThat(paymentStatus(orderNo)).isEqualTo("DONE");
        assertThat(paymentBalance(orderNo)).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("전액 취소: DB에 orders=CANCELED / payments=CANCELED / balance=0 으로 확정된다")
    void fullCancel_persistsCanceled() {
        String token = login("1", "user-local-only");
        String orderNo = createOrder(token, 1, 1);
        assertThat(confirm(token, orderNo, 10_000).getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<JsonNode> res = cancel(token, orderNo, 10_000, "전액 취소");
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(orderStatus(orderNo)).isEqualTo("CANCELED");
        assertThat(paymentStatus(orderNo)).isEqualTo("CANCELED");
        assertThat(paymentBalance(orderNo)).isEqualTo(0L);
    }

    @Test
    @DisplayName("부분 취소: DB에 orders=PAID 유지 / payments=PARTIAL_CANCELED / balance=7000 으로 확정된다")
    void partialCancel_keepsPaidAndPartial() {
        String token = login("1", "user-local-only");
        String orderNo = createOrder(token, 1, 1);
        assertThat(confirm(token, orderNo, 10_000).getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<JsonNode> res = cancel(token, orderNo, 3_000, "부분 취소");
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();

        // 부분취소는 주문을 PAID로 유지하고 결제만 PARTIAL_CANCELED로, 잔액은 7000으로 확정돼야 한다.
        assertThat(orderStatus(orderNo)).isEqualTo("PAID");
        assertThat(paymentStatus(orderNo)).isEqualTo("PARTIAL_CANCELED");
        assertThat(paymentBalance(orderNo)).isEqualTo(7_000L);
    }

    @Test
    @DisplayName("구매확정: 주문이 PAID여야 confirm-purchase가 통과(확정 증거)하고, 에스크로가 RELEASED로 확정된다")
    void purchaseConfirm_releasesEscrow() {
        String token = login("1", "user-local-only");
        String orderNo = createOrder(token, 1, 1);
        assertThat(confirm(token, orderNo, 10_000).getStatusCode().is2xxSuccessful()).isTrue();

        // 에스크로 HELD는 결제 승인 이벤트로 "비동기(Outbox, AFTER_COMMIT)" 생성된다. 구매확정의 릴리스는
        // 그 HELD가 있어야 성립하므로, HELD가 도착할 때까지 confirm-purchase를 짧게 재시도한다.
        // confirm-purchase가 2xx라는 것 자체가 "주문이 DB에 PAID로 확정됐다"는 증거다(PAID 아니면 서비스가 거부).
        boolean confirmed = pollUntil(() -> confirmPurchase(token, orderNo).getStatusCode().is2xxSuccessful());
        assertThat(confirmed).as("구매확정이 5초 내 2xx가 되어야 한다(HELD 생성 후 릴리스 성립)").isTrue();

        // 에스크로 홀드가 실 DB에 RELEASED로 확정됐는지 폴링으로 확인한다.
        boolean released = pollUntil(() -> "RELEASED".equals(escrowStatus(orderNo)));
        assertThat(released).as("에스크로 홀드가 5초 내 RELEASED로 확정되어야 한다").isTrue();
    }

    // --- DB 재조회 헬퍼: 응답이 아니라 확정된 실제 상태를 읽는다 ---

    private String orderStatus(String orderNo) {
        return jdbc.queryForObject("select status from orders where order_no=?", String.class, orderNo);
    }

    private String paymentStatus(String orderNo) {
        return jdbc.queryForObject("select status from payments where order_no=?", String.class, orderNo);
    }

    private long paymentBalance(String orderNo) {
        Long balance = jdbc.queryForObject(
                "select balance_amount from payments where order_no=?", Long.class, orderNo);
        return balance == null ? -1L : balance;
    }

    /** 에스크로 홀드는 비동기로 생성되므로 없을 수 있다 — 리스트로 받아 없으면 null을 돌려준다. */
    private String escrowStatus(String orderNo) {
        List<String> rows = jdbc.query(
                "select status from escrow_holds where order_no=?",
                (rs, rowNum) -> rs.getString("status"), orderNo);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 비동기 확정을 기다리는 단순 폴링(최대 ~5초, 200ms 간격). awaitility 의존을 더하지 않는다. */
    private boolean pollUntil(java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 25; i++) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
            } catch (RuntimeException ignored) {
                // 아직 준비 안 됨(빈 결과/일시 오류) — 다음 주기에 재시도
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // --- HTTP 헬퍼 (MySqlChaosTest 참고 + cancel/confirm-purchase 추가) ---

    private String login(String username, String password) {
        ResponseEntity<JsonNode> res = rest.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), json()),
                JsonNode.class);
        Assertions.assertTrue(res.getStatusCode().is2xxSuccessful(), "로그인 실패");
        return res.getBody().get("token").asText();
    }

    private String createOrder(String token, long productId, int quantity) {
        Map<String, Object> body = Map.of(
                "items", List.of(Map.of("productId", productId, "quantity", quantity)));
        ResponseEntity<JsonNode> res = rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), JsonNode.class);
        Assertions.assertTrue(res.getStatusCode().is2xxSuccessful(), "주문 생성 실패");
        return res.getBody().get("orderNo").asText();
    }

    private ResponseEntity<JsonNode> confirm(String token, String orderNo, long amount) {
        HttpHeaders headers = bearer(token);
        headers.set("Idempotency-Key", "it-" + System.nanoTime());
        Map<String, Object> body = Map.of(
                "paymentKey", "pk-" + orderNo,
                "orderNo", orderNo,
                "amount", amount,
                "pointAmount", 0);
        return rest.exchange("/api/v1/payments/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> cancel(String token, String orderNo, long cancelAmount, String reason) {
        HttpHeaders headers = bearer(token);
        headers.set("Idempotency-Key", "it-" + System.nanoTime());
        Map<String, Object> body = Map.of("cancelAmount", cancelAmount, "reason", reason);
        return rest.exchange("/api/v1/orders/" + orderNo + "/cancel", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> confirmPurchase(String token, String orderNo) {
        return rest.exchange("/api/v1/orders/" + orderNo + "/confirm-purchase", HttpMethod.POST,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = json();
        h.setBearerAuth(token);
        return h;
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }
}
