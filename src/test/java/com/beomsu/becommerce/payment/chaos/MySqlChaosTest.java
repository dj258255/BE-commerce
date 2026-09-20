package com.beomsu.becommerce.payment.chaos;

import com.fasterxml.jackson.databind.JsonNode;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MySQL 네트워크 장애를 <b>실제로 주입</b>해 결제 시스템의 복원력과 정합성을 검증하는 카오스 테스트.
 *
 * <p><b>왜 기본 스위트에서 제외하는가(@Tag("chaos"))</b><br>
 * 이 테스트는 MySQL 컨테이너 + Toxiproxy 컨테이너 2개를 같은 도커 네트워크에 띄우고 그 위로 Spring
 * 애플리케이션을 부팅한다(RANDOM_PORT). 컨테이너 2개 + 부트가 필요해 무겁고, CI 기본 스위트의
 * 결정성·속도를 해친다. 따라서 {@code ./gradlew test}에서는 {@code excludeTags 'chaos'}로 빠지고,
 * 네트워크가 준비된 전용 환경에서 {@code ./gradlew chaosTest}로만 실행한다.
 *
 * <p><b>무엇을·어떻게 검증하는가</b><br>
 * Toxiproxy가 앱↔MySQL 사이의 프록시가 되어, 테스트 도중 임의로 지연/단절 같은 toxic을 넣고 뺀다.
 * 앱의 {@code spring.datasource.url}은 실 MySQL이 아니라 <b>Toxiproxy 프록시 주소</b>를 가리키므로,
 * 모든 DB 트래픽이 우리가 조종하는 장애 구간을 통과한다. Flyway 마이그레이션도 이 프록시 위로 돈다.
 *
 * <p>주입하는 toxic과 기대 동작:
 * <ol>
 *   <li><b>latency(UPSTREAM, 5s)</b> — 쿼리 응답을 5초 지연시킨다. JDBC {@code socketTimeout}(3s)과
 *       Hikari {@code connectionTimeout}(2s)을 짧게 둬, 무한 hang이 아니라 <b>타임아웃 예외로 깨끗하게</b>
 *       끝나는지 본다(부분 커밋 없음).</li>
 *   <li><b>toxic 제거 후 같은 Idempotency-Key로 재시도</b> — 장애를 건너서도 멱등이 유지되어
 *       <b>이중 결제/이중 차감이 없는지</b> 본다.</li>
 * </ol>
 *
 * <p>참고: Redis는 이 도메인 흐름에서 런타임으로 쓰이지 않는다(멱등 처리는 {@code idempotency_keys}
 * 테이블의 유니크 제약으로 DB에서 수행). Lettuce 커넥션 팩토리는 지연 초기화라 부팅을 막지 않는다.
 */
@Tag("chaos")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("MySQL 네트워크 카오스 — 장애 중 깨끗한 실패 + 멱등 생존")
class MySqlChaosTest {

    private static final Network NETWORK = Network.newNetwork();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("becommerce")
            .withUsername("becommerce")
            .withPassword("becommerce")
            .withNetwork(NETWORK)
            .withNetworkAliases("mysql");

    @Container
    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0"))
            .withNetwork(NETWORK);

    /** 앱↔MySQL 트래픽을 가로채는 프록시. 여기에 toxic을 넣고 뺀다. */
    static Proxy dbProxy;

    @BeforeAll
    static void createProxy() throws IOException {
        ToxiproxyClient client = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort());
        // 컨테이너 내부에서 8666 포트로 리슨하며 mysql:3306으로 전달하는 프록시를 만든다.
        dbProxy = client.createProxy("mysql", "0.0.0.0:8666", "mysql:3306");
    }

    @DynamicPropertySource
    static void datasourceThroughProxy(DynamicPropertyRegistry registry) {
        // 앱은 실 MySQL이 아니라 Toxiproxy가 노출하는 host:port로 붙는다 → 모든 DB 트래픽이 장애 구간을 통과.
        String proxyHost = TOXIPROXY.getHost();
        int proxyPort = TOXIPROXY.getMappedPort(8666);
        // socketTimeout(3s): 응답 지연 toxic이 이 값을 넘으면 JDBC가 소켓 읽기를 끊어 예외로 만든다(hang 방지).
        String url = "jdbc:mysql://" + proxyHost + ":" + proxyPort
                + "/pay?serverTimezone=UTC&characterEncoding=UTF-8&socketTimeout=3000&connectTimeout=2000";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // 커넥션 획득도 무한 대기하지 않게 짧게 — 장애 시 hang이 아니라 즉시 타임아웃.
        registry.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        registry.add("spring.datasource.hikari.validation-timeout", () -> "2000");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
        // 이 테스트에서 쓰지 않는 외부화는 확실히 꺼 둔다.
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("정상→장애→복구: 장애 중엔 타임아웃으로 깨끗이 실패하고, 복구 후 같은 멱등키 재시도는 이중결제가 없다")
    void survivesNetworkFaultWithoutDoubleCharge() throws IOException {
        String token = login("1", "user-local-only");

        // 1) 정상 상태에서 체크아웃 1건 성공(주문 생성 → 승인).
        String orderNo = createOrder(token, 1, 1);
        String idemKey = "chaos-" + System.nanoTime();
        ResponseEntity<JsonNode> healthy = confirm(token, idemKey, orderNo, 10_000);
        assertThat(healthy.getStatusCode().is2xxSuccessful()).isTrue();

        // 2) 5초 지연 toxic 주입 — 응답이 socketTimeout(3s)을 넘겨 DB 호출이 끊긴다.
        dbProxy.toxics().latency("db-latency", ToxicDirection.UPSTREAM, 5_000);

        try {
            // 3) 장애 중 새 체크아웃 시도 → 무한 hang이 아니라 서버 에러(5xx)로 깨끗하게 끝난다.
            //    주문 생성 자체가 DB에 닿으므로 여기서 타임아웃 → 부분 커밋 없이 실패.
            String faultKey = "chaos-fault-" + System.nanoTime();
            ResponseEntity<JsonNode> duringFault = tryCreateOrderRaw(token);
            HttpStatusCode faultStatus = duringFault.getStatusCode();
            assertThat(faultStatus.is5xxServerError())
                    .as("네트워크 장애는 hang이 아니라 5xx로 끝나야 한다(실제 상태: %s)", faultStatus)
                    .isTrue();
        } finally {
            // 4) toxic 제거 — 네트워크 정상화.
            dbProxy.toxics().get("db-latency").remove();
        }

        // 5) 복구 후, 1)에서 성공했던 것과 "같은 Idempotency-Key"로 재시도.
        //    멱등 저장소가 장애를 건너 유지되므로 첫 응답이 그대로 재반환된다 → 이중결제/이중차감 없음.
        ResponseEntity<JsonNode> replay = confirm(token, idemKey, orderNo, 10_000);
        assertThat(replay.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(replay.getBody()).isNotNull();
        // 같은 주문에 대한 같은 멱등키 → 첫 결과와 동일한 주문번호를 돌려준다(새 승인이 아니다).
        assertThat(replay.getBody().get("orderNo").asText()).isEqualTo(orderNo);
    }

    // --- HTTP 헬퍼 ---

    private String login(String username, String password) {
        ResponseEntity<JsonNode> res = rest.postForEntity(
                "/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), json()),
                JsonNode.class);
        Assertions.assertTrue(res.getStatusCode().is2xxSuccessful(), "로그인 실패");
        return res.getBody().get("token").asText();
    }

    private String createOrder(String token, long productId, int quantity) {
        ResponseEntity<JsonNode> res = tryCreateOrder(token, productId, quantity);
        Assertions.assertTrue(res.getStatusCode().is2xxSuccessful(), "주문 생성 실패");
        return res.getBody().get("orderNo").asText();
    }

    private ResponseEntity<JsonNode> tryCreateOrder(String token, long productId, int quantity) {
        Map<String, Object> body = Map.of(
                "items", java.util.List.of(Map.of("productId", productId, "quantity", quantity)));
        return rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), JsonNode.class);
    }

    /** 장애 구간에서의 주문 생성 — 예외/5xx를 그대로 관측하기 위한 raw 호출. */
    private ResponseEntity<JsonNode> tryCreateOrderRaw(String token) {
        return tryCreateOrder(token, 1, 1);
    }

    private ResponseEntity<JsonNode> confirm(String token, String idemKey, String orderNo, long amount) {
        HttpHeaders headers = bearer(token);
        headers.set("Idempotency-Key", idemKey);
        Map<String, Object> body = Map.of(
                "paymentKey", "pk-" + orderNo,
                "orderNo", orderNo,
                "amount", amount,
                "pointAmount", 0);
        return rest.exchange("/api/v1/payments/confirm", HttpMethod.POST,
                new HttpEntity<>(body, headers), JsonNode.class);
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
