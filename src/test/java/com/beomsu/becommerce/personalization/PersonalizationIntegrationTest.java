package com.beomsu.becommerce.personalization;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 개인화 수집·읽기를 <b>실 MySQL + 실 Redis</b>로 검증한다.
 *
 * <p>{@code transport=IN_REQUEST}로 고정해 기동한다. 이유는 두 가지다 — ① Kafka 없이도 전체 경로
 * (HTTP → DB → 컨텍스트 저장소 → HTTP)를 한 번에 지나갈 수 있고, ② {@code IN_REQUEST}가 이 모듈에서
 * <b>동기 경로를 가진 유일한 방식</b>이라 테스트가 결정적이다. Kafka 경로는 와이어 파싱을
 * {@code KafkaContextTransportTest}가, 브로커를 지나는 실 경로는 실기동 확인이 맡는다.
 *
 * <p>단언은 응답이 아니라 <b>DB와 Redis를 직접 재조회</b>한다 — 응답만 맞고 저장이 안 되는 회귀는
 * 이 저장소가 실제로 겪은 실패다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("개인화 통합 — 활동이 DB에 확정되고 컨텍스트가 저장소에 반영된다")
class PersonalizationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("becommerce")
            .withUsername("becommerce")
            .withPassword("becommerce");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        String url = MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8";
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.kafka.bootstrap-servers", () -> "");
        // 컨텍스트가 요청 안에서 갱신되어야 HTTP 한 번으로 전체 경로를 볼 수 있다.
        registry.add("app.personalization.transport", () -> "IN_REQUEST");
    }

    @Autowired
    TestRestTemplate rest;

    /** 같은 사용자로 여러 번 로그인하지 않는다 — IP 기준 초당 한도(5/s)에 걸린다. */
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @BeforeEach
    void clean() {
        jdbc.update("delete from user_activities");
        // 키 접두어 패턴으로 지운다 — 키에 값 판이 붙으면(:v2) 하드코딩한 키가 어긋난다.
        var keys = redis.keys("ctx:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    @Test
    @DisplayName("활동이 DB에 확정되고 컨텍스트에 즉시 반영된다 — 응답이 아니라 DB와 Redis를 본다")
    void activityIsPersistedAndApplied() {
        String token = login("1");

        ResponseEntity<JsonNode> res = activity(token, 42L, "CLICK", 3L);
        assertThat(res.getStatusCode().value()).isEqualTo(201);
        assertThat(res.getBody().get("source").asText()).isEqualTo("SYNTHETIC");

        // DB — 응답이 아니라 실제 행.
        assertThat(jdbc.queryForObject(
                "select count(*) from user_activities where user_id = 1 and seq = 3", Long.class)).isEqualTo(1L);
        // 저장소 — 컨텍스트 값이 실제로 들어갔다. 키에 값 판이 붙으므로(:v2) 패턴으로 찾는다.
        var storedKeys = redis.keys("ctx:*");
        assertThat(storedKeys).isNotEmpty();
        String stored = redis.opsForValue().get(storedKeys.iterator().next());
        assertThat(stored).isNotNull().contains("\"seq\":3");

        JsonNode context = context(token, 3L, 200L);
        assertThat(context.get("reflected").asBoolean()).isTrue();
        assertThat(context.get("source").asText()).isEqualTo("CONTEXT");
        assertThat(context.get("seq").asLong()).isEqualTo(3L);
    }

    @Test
    @DisplayName("같은 (userId, seq)를 두 번 보내면 유니크 제약이 막는다 — DB는 1건")
    void duplicateSeqIsRejected() {
        String token = login("1");

        assertThat(activity(token, 42L, "CLICK", 1L).getStatusCode().value()).isEqualTo(201);
        // 두 번째는 유니크에 부딪힌다. 500이 아니라 도메인 규칙 위반으로 알려야 한다.
        assertThat(activity(token, 42L, "CLICK", 1L).getStatusCode().value()).isEqualTo(409);

        assertThat(jdbc.queryForObject("select count(*) from user_activities", Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("순번이 낮은 이벤트는 컨텍스트를 되돌리지 않는다 — 순서 역전 방어")
    void staleSeqDoesNotRewindContext() {
        String token = login("1");

        activity(token, 42L, "CLICK", 5L);
        activity(token, 43L, "VIEW", 4L);

        JsonNode context = context(token, 5L, 0L);
        assertThat(context.get("seq").asLong()).isEqualTo(5L);
        // 두 이벤트가 DB에는 둘 다 남는다 — 로그는 전부 남기고 컨텍스트만 순서를 지킨다.
        assertThat(jdbc.queryForObject("select count(*) from user_activities", Long.class)).isEqualTo(2L);
    }

    @Test
    @DisplayName("활동이 없으면 컨텍스트는 EMPTY로 폴백한다 — 폴백을 숨기지 않는다")
    void emptyContextWhenNoActivity() {
        String token = login("1");

        JsonNode context = context(token, null, 0L);

        assertThat(context.get("source").asText()).isEqualTo("EMPTY");
        assertThat(context.get("reflected").asBoolean()).isFalse();
        assertThat(context.get("itemCount").asInt()).isZero();
    }

    @Test
    @DisplayName("비로그인은 401 — 개인화 표면은 본인 것만 다룬다")
    void anonymousIsRejected() {
        assertThat(rest.getForEntity("/api/v1/personalization/context", JsonNode.class)
                .getStatusCode().value()).isEqualTo(401);
        assertThat(rest.exchange("/api/v1/personalization/activity", HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", 1, "type", "CLICK", "seq", 1), json()), JsonNode.class)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("잘못된 입력은 400 — seq는 1 이상, 유형은 CLICK/VIEW만")
    void invalidRequestIsRejected() {
        String token = login("1");

        assertThat(activity(token, 42L, "CLICK", 0L).getStatusCode().value()).isEqualTo(400);
        assertThat(activity(token, 42L, "PURCHASE", 1L).getStatusCode().value()).isEqualTo(400);
    }

    // --- 헬퍼 ---

    private ResponseEntity<JsonNode> activity(String token, long itemId, String type, long seq) {
        return rest.exchange("/api/v1/personalization/activity", HttpMethod.POST,
                new HttpEntity<>(Map.of("itemId", itemId, "type", type, "seq", seq), bearer(token)),
                JsonNode.class);
    }

    private JsonNode context(String token, Long expectSeq, long waitMs) {
        String query = "?waitMs=" + waitMs + (expectSeq == null ? "" : "&expectSeq=" + expectSeq);
        ResponseEntity<JsonNode> res = rest.exchange("/api/v1/personalization/context" + query,
                HttpMethod.GET, new HttpEntity<>(null, bearer(token)), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        return res.getBody();
    }

    /**
     * 토큰은 **클래스당 사용자마다 한 번만** 받는다.
     *
     * <p>테스트마다 로그인하면 같은 IP로 1초에 6~7건이 나가는데, {@code RateLimitFilter}가
     * 미인증 진입점(로그인·회원가입)을 <b>IP + 경로 기준 초당 5건</b>으로 막는다
     * ({@code app.ratelimit.per-user-per-sec}). 그러면 로그인이 429로 거절되어 테스트가
     * "로그인 실패"로 죽는다 — CI에서 실제로 그렇게 죽었다(#177).
     */
    private String authToken(String username) {
        return TOKENS.computeIfAbsent(username, this::login);
    }

    private String login(String username) {
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", "user-local-only"), json()),
                JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful())
                .describedAs("로그인 실패 — status=%s body=%s (IP 기준 초당 한도에 걸렸을 수 있다)",
                        res.getStatusCode(), res.getBody())
                .isTrue();
        return res.getBody().get("token").asText();
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
