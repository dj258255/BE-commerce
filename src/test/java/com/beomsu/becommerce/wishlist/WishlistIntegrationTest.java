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
        String token = login("1", "user-local-only");

        assertThat(add(token, PRODUCT).getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(add(token, PRODUCT).getStatusCode().is2xxSuccessful()).isTrue();

        assertThat(countFor(1L, PRODUCT)).isEqualTo(1);
    }

    @Test
    @DisplayName("추가 → 삭제 → 추가가 같은 상태로 돌아온다")
    void addRemoveAddReturnsToSameState() {
        String token = login("1", "user-local-only");

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
        String user1 = login("1", "user-local-only");
        String user2 = login("2", "user-local-only");

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
        String token = login("1", "user-local-only");

        ResponseEntity<JsonNode> res = add(token, MISSING_PRODUCT);

        assertThat(res.getStatusCode().value()).isEqualTo(404);
        assertThat(res.getBody().get("code").asText()).isEqualTo("PRODUCT_NOT_FOUND");
        // 거절됐으면 아무것도 남지 않아야 한다.
        assertThat(jdbc.queryForObject("select count(*) from wishlist_items", Long.class)).isZero();
    }

    @Test
    @DisplayName("찜하지 않은 상품을 지워도 204 — 삭제도 멱등이다")
    void removeIsIdempotent() {
        String token = login("1", "user-local-only");

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

    private String login(String username, String password) {
        ResponseEntity<JsonNode> res = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(Map.of("username", username, "password", password), json()), JsonNode.class);
        assertThat(res.getStatusCode().is2xxSuccessful()).describedAs("로그인 실패").isTrue();
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
