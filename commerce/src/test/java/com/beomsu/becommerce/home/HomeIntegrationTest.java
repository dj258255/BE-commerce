package com.beomsu.becommerce.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.beomsu.becommerce.home.internal.ImpressionCleanupScheduler;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 홈 표면의 <b>경계</b>를 실제 기동으로 고정한다 (M7).
 *
 * <p>여기서 지키려는 것은 성능이 아니라 <b>홈이 어떤 경우에도 서는가</b>다:
 * <ul>
 *   <li><b>비로그인은 401</b> — 홈은 그 사용자의 활동으로 조립되므로 남이 만들 수 있으면 정보 노출이다</li>
 *   <li><b>모델을 못 불러도 200</b> — 상점 첫 화면이 통째로 실패로 보이면 안 된다. 폴백은 정상 응답이다</li>
 *   <li><b>버린 것을 응답이 밝힌다</b> — {@code stats} 가 없으면 "왜 이 화면인가"를 복원할 수 없다</li>
 * </ul>
 *
 * <p>이 테스트의 카탈로그는 마이그레이션 시드뿐이라 작다(실제 H&M 카탈로그는 파이프라인이 적재한다).
 * 그래도 <b>경로가 살아 있는가</b>는 여기서 확인된다 — 조립 규칙의 효과는 실측 리포트가 맡는다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("홈 표면 통합 — 본인만 조립되고, 모델이 죽어도 200 이며, 버린 것을 밝힌다")
class HomeIntegrationTest {

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
        registry.add("spring.datasource.url", () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.kafka.bootstrap-servers", () -> "");
        // 컨텍스트가 요청 안에서 갱신돼야 홈이 "최근 본 상품"을 볼 수 있다(결정적이다).
        registry.add("app.personalization.transport", () -> "IN_REQUEST");
    }

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ImpressionCleanupScheduler cleanup;

    @Test
    @DisplayName("보존 정리는 만료 행만 지운다 — 안 지우면 표가 하루 260만 행으로 자란다")
    void cleanupDeletesOnlyExpiredRows() {
        jdbc.update("delete from home_impressions");
        // 만료 행은 SQL 로 직접 심는다(앱의 기록 경로는 항상 '지금'을 쓴다).
        jdbc.update("insert into home_impressions (user_id, source, total_ms, model_ms, constraint_ms,"
                + " row_count, item_count, item_ids, created_at) values (?,?,?,?,?,?,?,?,?)",
                1L, "MODEL", 1L, 1L, 1L, 1, 1, "1", java.sql.Timestamp.from(
                        java.time.Instant.now().minus(java.time.Duration.ofDays(8))));
        String token = login();
        rest.exchange("/api/v1/personalization/homepage", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);

        cleanup.run();

        // 응답이 아니라 DB 를 본다 — 오래된 행은 사라지고 방금 낸 행은 남아야 한다.
        assertThat(jdbc.queryForObject(
                "select count(*) from home_impressions where created_at < now() - interval 7 day", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from home_impressions", Integer.class))
                .isPositive();
    }

    @Test
    @DisplayName("비로그인은 401 — 홈은 그 사용자의 활동으로 조립된다")
    void anonymousIsRejected() {
        assertThat(rest.getForEntity("/api/v1/personalization/homepage", String.class)
                .getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("홈 응답 하나가 노출 기록 한 행을 남긴다 — 응답이 아니라 DB를 본다")
    void homeResponseIsRecorded() {
        jdbc.update("delete from home_impressions");
        String token = login();

        rest.exchange("/api/v1/personalization/homepage", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);

        // 응답만 맞고 저장이 안 되는 회귀는 이 저장소가 실제로 겪은 실패다 — 표를 직접 본다.
        Map<String, Object> row = jdbc.queryForMap(
                "select user_id, source, row_count, item_count, item_ids from home_impressions order by id desc limit 1");
        assertThat(row.get("user_id")).isEqualTo(1L);
        assertThat(row.get("source")).isIn("MODEL", "FALLBACK");
        // 행·항목 수가 실제 노출과 같아야 한다(둘이 갈라지면 기록이 거짓이 된다).
        assertThat(((Number) row.get("item_count")).intValue())
                .isEqualTo(countItems(row.get("item_ids").toString()));
    }

    private static int countItems(String itemIds) {
        return itemIds == null || itemIds.isBlank() ? 0 : itemIds.split(",").length;
    }

    @Test
    @DisplayName("로그인하면 200 이고 행이 서며, 무엇을 버렸는지 응답이 밝힌다")
    void homeIsAssembledAndReportsWhatItDropped() {
        String token = login();

        ResponseEntity<JsonNode> res = rest.exchange("/api/v1/personalization/homepage", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = res.getBody();
        // 출처가 어느 쪽이든 홈은 서야 한다 — 폴백도 설계된 응답이다.
        assertThat(body.get("source").asText()).isIn("MODEL", "FALLBACK");
        // 버린 것을 밝히는 계기 — 이 필드가 사라지면 "왜 이 화면인가"를 복원할 수 없다.
        assertThat(body.has("stats")).isTrue();
        assertThat(body.get("stats").has("duplicates")).isTrue();
        assertThat(body.get("stats").has("cappedOut")).isTrue();
        // 응답의 행 구조가 계약대로다(비어 있어도 필드는 있어야 화면이 분기할 수 있다).
        assertThat(body.has("rows")).isTrue();
        assertThat(body.has("latency")).isTrue();
        assertThat(body.get("latency").has("inferenceMs")).isTrue();
    }

    private String login() {
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", "1", "password", "user-local-only"), json()), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).describedAs("로그인 실패").isTrue();
        return res.getBody().get("token").asText();
    }

    private HttpHeaders json() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = json();
        h.setBearerAuth(token);
        return h;
    }
}
