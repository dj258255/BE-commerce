package com.beomsu.becommerce.wishlist;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 위시리스트가 <b>실제 MySQL에 확정되는지</b>와 <b>유니크 제약이 멱등을 지키는지</b>를 실 DB로 검증한다.
 *
 * <p>단위 테스트는 리포지토리를 목으로 두므로 "서비스가 save를 한 번 부른다"까지만 본다. 여기서는
 * HTTP <b>응답</b>이 아니라 {@link JdbcTemplate}로 테이블을 <b>직접 재조회</b>해 행 수를 센다 —
 * 멱등의 최종 근거는 서비스 코드가 아니라 <b>DB의 유니크 제약</b>이기 때문이다.
 *
 * <p>{@code @Tag("integration")}이라 기본 스위트에서 제외된다(`build.gradle`).
 * CI는 {@code ./gradlew integrationTest}로 돌린다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("위시리스트 통합 — 실 MySQL에 확정되고, 유니크 제약이 멱등을 지킨다")
class WishlistIntegrationTest {

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
    }

    /** V2 시드의 레거시 상품 — V55가 4~36을 은퇴시켰지만 1~3은 부하·통합 테스트가 써서 남겼다. */
    private static final long PRODUCT = 1L;
    private static final long MISSING_PRODUCT = 999_999_999L;

    @Autowired
    TestRestTemplate rest;

    /** 같은 사용자로 여러 번 로그인하지 않는다 — IP 기준 초당 한도(5/s)에 걸린다. */
    private static final Map<String, String> TOKENS = new ConcurrentHashMap<>();

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void cleanWishlist() {
        jdbc.update("delete from wishlist_items");
    }

    // --- 테스트 케이스: 응답이 아니라 DB에 남은 행을 센다 ---

    @Test
    @DisplayName("같은 상품을 두 번 찜해도 1건 — 유니크 제약이 멱등을 지킨다")
    void addIsIdempotentAtDbLevel() {
        String token = authToken("1");

        assertThat(add(token, PRODUCT).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(add(token, PRODUCT).getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(countFor(1L, PRODUCT)).isEqualTo(1);
    }

    @Test
    @DisplayName("추가 → 삭제 → 추가가 같은 상태로 돌아온다")
    void addRemoveAddReturnsToSameState() {
        String token = authToken("1");

        add(token, PRODUCT);
        assertThat(countFor(1L, PRODUCT)).isEqualTo(1);

        assertThat(remove(token, PRODUCT).getStatusCode().value()).isEqualTo(204);
        assertThat(countFor(1L, PRODUCT)).isZero();

        add(token, PRODUCT);
        assertThat(countFor(1L, PRODUCT)).isEqualTo(1);
        // 되돌아온 상태가 첫 상태와 같은 모양인지 — 목록에 그 상품이 있다.
        assertThat(listProductIds(token)).containsExactly(PRODUCT);
    }

    @Test
    @DisplayName("남의 위시리스트는 보이지 않는다 — 1번이 찜한 것이 2번 목록에 없다")
    void doesNotLeakAnotherUsersWishlist() {
        String user1 = authToken("1");
        String user2 = authToken("2");

        add(user1, PRODUCT);

        assertThat(countFor(1L, PRODUCT)).isEqualTo(1);
        assertThat(listProductIds(user1)).containsExactly(PRODUCT);
        assertThat(listProductIds(user2)).isEmpty();
        assertThat(countFor(2L, PRODUCT)).isZero();
    }

    @Test
    @DisplayName("비로그인은 401 — 찜은 서버 저장이므로 인증이 필요하다")
    void anonymousIsRejected() {
        ResponseEntity<JsonNode> res = rest.getForEntity("/api/v1/wishlist", JsonNode.class);

        assertThat(res.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    @DisplayName("없는 상품을 찜하면 404 PRODUCT_NOT_FOUND — 응답 코드와 DB 무변화를 함께 본다")
    void missingProductIsRejected() {
        String token = authToken("1");

        ResponseEntity<JsonNode> res = add(token, MISSING_PRODUCT);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        // 거절됐으면 아무것도 남지 않아야 한다.
        assertThat(jdbc.queryForObject("select count(*) from wishlist_items", Long.class)).isZero();
    }

    @Test
    @DisplayName("찜하지 않은 상품을 지워도 204 — 삭제도 멱등이다")
    void removeIsIdempotent() {
        String token = authToken("1");

        assertThat(remove(token, PRODUCT).getStatusCode().value()).isEqualTo(204);
        assertThat(remove(token, PRODUCT).getStatusCode().value()).isEqualTo(204);
        assertThat(jdbc.queryForObject("select count(*) from wishlist_items", Long.class)).isZero();
    }

    // --- 헬퍼 ---

    private long countFor(long userId, long productId) {
        return jdbc.queryForObject(
                "select count(*) from wishlist_items where user_id = ? and product_id = ?",
                Long.class, userId, productId);
    }

    private List<Long> listProductIds(String token) {
        ResponseEntity<JsonNode> res = rest.exchange("/api/v1/wishlist", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        List<Long> ids = new ArrayList<>();
        res.getBody().forEach(node -> ids.add(node.get("productId").asLong()));
        return ids;
    }

    private ResponseEntity<JsonNode> add(String token, long productId) {
        return rest.exchange("/api/v1/wishlist", HttpMethod.POST,
                new HttpEntity<>(Map.of("productId", productId), bearer(token)), JsonNode.class);
    }

    private ResponseEntity<JsonNode> remove(String token, long productId) {
        return rest.exchange("/api/v1/wishlist/" + productId, HttpMethod.DELETE,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);
    }

    /**
     * 토큰은 **클래스당 사용자마다 한 번만** 받는다.
     *
     * <p>테스트마다 로그인하면 같은 IP로 1초에 6~7건이 나가는데, {@code RateLimitFilter}가 미인증
     * 진입점(로그인·회원가입)을 <b>IP + 경로 기준 초당 5건</b>으로 막는다
     * ({@code app.ratelimit.per-user-per-sec}). 그러면 로그인이 429로 거절되어 테스트가
     * "로그인 실패"로 죽는다 — CI에서 실제로 그렇게 죽었다(#177).
     */
    private String authToken(String username) {
        return TOKENS.computeIfAbsent(username, u -> login(u, "user-local-only"));
    }

    private String login(String username, String password) {
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), json()), JsonNode.class);
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
