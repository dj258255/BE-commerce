package com.beomsu.becommerce.fraud;

import com.beomsu.becommerce.fraud.internal.FraudCheckRequest;
import com.beomsu.becommerce.fraud.internal.FraudService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>판정과 승인을 합친 뒤의 종단 지연</b>을 잰다. 그동안 안 재고 더하기만 하던 자리다.
 *
 * <p>{@code docs/17} 은 "승인 경로 p99 105.4ms + FDS 판정 p99 1.9ms = 약 107ms" 라고 적어 뒀다.
 * <b>이 덧셈이 틀렸다.</b> 두 값은 서로 다른 실행에서 따로 잰 것이고, 무엇보다
 * <b>합의 p99 는 p99 의 합이 아니다.</b> 두 단계의 느린 순간이 같은 요청에서 겹칠 때만 그 합이
 * 나오는데, 그 확률은 각각이 느릴 확률의 곱에 가깝다. 그래서 보통은 덧셈보다 작게 나온다.
 * 반대로 둘이 같은 자원(여기서는 같은 기계·같은 커넥션 풀)에서 밀리면 더 크게도 나온다.
 * 어느 쪽인지는 <b>같은 요청에서 같이 재야</b> 알 수 있다.
 *
 * <p><b>어떻게 재나</b>: 실 MySQL·실 Redis 위에 애플리케이션을 통째로 띄우고, 요청 하나마다
 * 판정 시간과 승인 시간을 <b>연달아</b> 잰 뒤 그 둘의 합을 요청 단위로 모은다.
 * {@code FraudService} 는 이 애플리케이션의 빈을 그대로 쓴다.
 *
 * <p><b>한계</b>: 판정이 HTTP 요청 스레드 <b>안</b>이 아니라 그 직전에 돈다. 실제로 붙이면
 * 컨트롤러 안에서 돌 것이므로 커넥션 풀 경합 양상이 조금 다르다. 재는 것은 <b>합의 분포</b>이고,
 * 그 분포가 덧셈과 얼마나 다른지가 이 테스트의 답이다.
 *
 * <p>수치를 통과 조건으로 걸지 않는다. 기계마다 달라지는 값을 임계로 걸면 CI 가 환경을 잰다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("FDS 를 승인 경로에 넣었을 때의 종단 지연 — 덧셈이 맞는지")
class FdsInlineCombinedLatencyTest {

    private static final int WARMUP = 40;
    private static final int SAMPLES = 2_000;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("becommerce").withUsername("becommerce").withPassword("becommerce");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.kafka.bootstrap-servers", () -> "");
        // 레이트리밋을 <b>끄지 않고 올린다.</b> 기본값은 사용자당 초당 5건이라 연속 승인이 429 로
        // 막힌다. 끄면 필터가 경로에서 빠져 그 Redis 왕복까지 같이 사라지므로, 재는 값이
        // 실제 승인 경로보다 짧아진다. 상한만 올려서 필터는 그대로 돌게 둔다.
        registry.add("app.ratelimit.per-user-per-sec", () -> "1000000");
        registry.add("app.ratelimit.global-per-sec", () -> "1000000");
    }

    @Autowired
    TestRestTemplate rest;

    /** 이 애플리케이션이 실제로 쓰는 판정 빈. 목이 아니라 실 Redis 를 세 번 왕복한다. */
    @Autowired
    FraudService fraudService;

    @Test
    @DisplayName("판정 시간과 승인 시간을 같은 요청에서 재고, 합의 p99 를 덧셈과 견준다")
    void measureCombinedLatency() {
        String token = login();

        for (int i = 0; i < WARMUP; i++) {
            runOnce(token, i, null, null, null);
        }

        long[] fds = new long[SAMPLES];
        long[] approve = new long[SAMPLES];
        long[] combined = new long[SAMPLES];
        for (int i = 0; i < SAMPLES; i++) {
            runOnce(token, i, fds, approve, combined);
        }
        Arrays.sort(fds);
        Arrays.sort(approve);
        Arrays.sort(combined);

        double sumOfP99s = ms(approve, 0.99) + ms(fds, 0.99);
        double measuredP99 = ms(combined, 0.99);

        System.out.printf("%n╔══ 판정+승인 종단 지연 (실 MySQL·실 Redis, %d회) ══%n", SAMPLES);
        row("FDS 판정", fds);
        row("승인 경로", approve);
        row("합 (같은 요청)", combined);
        System.out.printf("%n  덧셈으로 낸 p99   %.2f ms  (승인 p99 + 판정 p99)%n", sumOfP99s);
        System.out.printf("  실제로 잰 p99     %.2f ms%n", measuredP99);
        System.out.printf("  차이              %+.2f ms (%+.1f%%)%n",
                measuredP99 - sumOfP99s,
                sumOfP99s == 0 ? 0 : (measuredP99 - sumOfP99s) / sumOfP99s * 100);
        System.out.printf("  판정이 차지하는 몫 %.2f%% (합의 p50 기준)%n",
                ms(combined, 0.50) == 0 ? 0 : ms(fds, 0.50) / ms(combined, 0.50) * 100);
        System.out.println("╚═══════════════════════════════════════════════");

        assertThat(combined).hasSize(SAMPLES);
    }

    /** 주문 생성은 안 잰다. 재는 것은 판정과 승인이다. */
    private void runOnce(String token, int i, long[] fds, long[] approve, long[] combined) {
        String orderNo = createOrder(token);

        long t0 = System.nanoTime();
        // 키를 매번 바꾼다. 같은 키만 두드리면 Redis 가 아니라 캐시 친화성을 재게 된다.
        fraudService.evaluate(new FraudCheckRequest(
                i, "card-" + i, "10.0." + (i % 250) + ".1", "dev-" + (i % 500), 10_000L, 0));
        long t1 = System.nanoTime();
        ResponseEntity<JsonNode> res = confirm(token, orderNo);
        long t2 = System.nanoTime();

        Assertions.assertTrue(res.getStatusCode().is2xxSuccessful(), "승인 실패: " + res.getStatusCode());
        if (fds != null) {
            fds[i] = t1 - t0;
            approve[i] = t2 - t1;
            combined[i] = t2 - t0;
        }
    }

    private static void row(String label, long[] sorted) {
        System.out.printf("  %-14s p50 %7.2f ms   p95 %7.2f ms   p99 %7.2f ms   max %7.2f ms%n",
                label, ms(sorted, 0.50), ms(sorted, 0.95), ms(sorted, 0.99),
                sorted[sorted.length - 1] / 1_000_000.0);
    }

    private static double ms(long[] sorted, double q) {
        return sorted[(int) Math.min(sorted.length - 1, Math.round(q * sorted.length))] / 1_000_000.0;
    }

    // --- HTTP 헬퍼 (PaymentPersistenceIntegrationTest 와 같은 흐름) ---

    private String login() {
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", "1", "password", "user-local-only"), json()),
                JsonNode.class);
        Assertions.assertTrue(res.getStatusCode().is2xxSuccessful(), "로그인 실패");
        return res.getBody().get("token").asText();
    }

    private String createOrder(String token) {
        Map<String, Object> body = Map.of("items", List.of(Map.of("productId", 1, "quantity", 1)));
        ResponseEntity<JsonNode> res = rest.exchange("/api/v1/orders", HttpMethod.POST,
                new HttpEntity<>(body, bearer(token)), JsonNode.class);
        Assertions.assertTrue(res.getStatusCode().is2xxSuccessful(), "주문 생성 실패");
        return res.getBody().get("orderNo").asText();
    }

    private ResponseEntity<JsonNode> confirm(String token, String orderNo) {
        HttpHeaders headers = bearer(token);
        headers.set("Idempotency-Key", "fds-lat-" + System.nanoTime());
        Map<String, Object> body = Map.of(
                "paymentKey", "pk-" + orderNo, "orderNo", orderNo, "amount", 10_000, "pointAmount", 0);
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
