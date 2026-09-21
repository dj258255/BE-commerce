package com.beomsu.becommerce.recommendation;

import com.beomsu.becommerce.order.StockAvailabilityFacts;
import com.beomsu.becommerce.recommendation.internal.ItemPool;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 추천 표면의 <b>경계</b>를 실제 기동으로 고정한다 (E4 · ADR-038).
 *
 * <p>여기서 지키려는 것은 성능이 아니라 <b>열려 있으면 안 되는 것이 닫혀 있는가</b>다.
 * <ul>
 *   <li><b>실험 계기는 기본 off 여야 한다.</b> 이 엔드포인트는 요청으로 상품 가용성을 바꾼다 —
 *       실험 밖에서 열려 있으면 그 자체가 결함이다. 기본 프로파일에서 <b>404</b>여야 한다
 *       (빈이 아예 만들어지지 않는다). 이 테스트는 그 잠금이 실제로 걸려 있는지 본다</li>
 *   <li><b>제약 정책이 응답에 드러나야 한다.</b> 창(snapshotAgeMs)을 못 재면 E4 의 결론을
 *       재현할 수 없다 — 필드가 사라지면 실험이 조용히 무의미해진다</li>
 *   <li><b>추천은 모델이 죽어도 200 이다.</b> 폴백은 설계된 응답이라는 E3 의 계약이
 *       제약 확인을 붙이면서 깨지지 않았는지 확인한다</li>
 * </ul>
 *
 * <p>과부하 정책은 여기서 다루지 않는다(E3 의 몫이고 `OverloadGateTest` 가 본다). 이 테스트는
 * <b>기본 설정으로 뜬 앱</b>이 정상이라는 것까지만 본다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@DisplayName("추천 표면 통합 — 실험 계기는 닫혀 있고 제약 정책은 응답에 드러난다")
class RecommendationIntegrationTest {

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
    }

    @Autowired
    TestRestTemplate rest;

    /** 실제 재고 읽기 포트 — 실험 재고 시드가 실험 풀을 덮는지 확인한다. */
    @Autowired
    StockAvailabilityFacts stockFacts;

    @Test
    @DisplayName("실험 계기는 기본값으로 닫혀 있다 — 404(빈이 만들어지지 않는다)")
    void experimentEndpointIsClosedByDefault() {
        String token = login();

        ResponseEntity<String> state = rest.exchange("/api/v1/experiments/constraint/state",
                HttpMethod.GET, new HttpEntity<>(null, bearer(token)), String.class);
        ResponseEntity<String> swap = rest.exchange("/api/v1/experiments/constraint/swap?count=6",
                HttpMethod.POST, new HttpEntity<>(null, bearer(token)), String.class);
        ResponseEntity<String> prime = rest.exchange("/api/v1/experiments/constraint/prime?count=6",
                HttpMethod.POST, new HttpEntity<>(null, bearer(token)), String.class);

        // 401/403 이 아니라 404 다 — 경로가 없어야 한다(있으면 그 자체가 결함이다).
        assertThat(state.getStatusCode().value()).isEqualTo(404);
        assertThat(swap.getStatusCode().value()).isEqualTo(404);
        assertThat(prime.getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("실험 재고 시드(V60)가 실험 풀을 덮는다 — SQL 과 ItemPool 이 갈라지면 추천이 빈다")
    void experimentStockSeedCoversPool() {
        List<Long> pool = ItemPool.experimentPool();

        assertThat(pool).isNotEmpty();
        assertThat(stockFacts.unavailableAmong(pool))
                .as("시드가 빠진 id 가 있으면 fail-closed 로 전부 걸러진다 — 추천이 조용히 빈다")
                .isEmpty();
    }

    @Test
    @DisplayName("추천 응답이 제약 정책과 창을 밝힌다 — 이 필드가 사라지면 E4 를 재현할 수 없다")
    void recommendationExposesConstraintState() {
        String token = login();

        ResponseEntity<JsonNode> res = rest.exchange("/api/v1/recommendations", HttpMethod.GET,
                new HttpEntity<>(null, bearer(token)), JsonNode.class);

        assertThat(res.getStatusCode().is2xxSuccessful()).isTrue();
        JsonNode body = res.getBody();
        // 기본 정책은 E4 측정으로 정했다(ADR-038). 값이 바뀌면 이 테스트가 먼저 알려 준다.
        assertThat(body.get("constraintPolicy").asText()).isEqualTo("AFTER_GENERATION");
        assertThat(body.has("violations")).isTrue();
        assertThat(body.has("servingMs")).isTrue();
        assertThat(body.has("auditMs")).isTrue();
        // E4 후속의 비용 축 — 확인에 쓴 시간. 응답에서 사라지면 교환비를 계산할 수 없다.
        assertThat(body.has("checkMs")).isTrue();
        // E5 의 범위 — 기본값은 측정으로 정했다(ADR-040). 값이 바뀌면 이 테스트가 먼저 알려 준다.
        assertThat(body.get("generationScope").asText()).isEqualTo("RANKING");
        // 예산 구간 — 이 필드가 사라지면 "모델이 예산을 얼마나 먹는가"를 계산할 수 없다.
        assertThat(body.has("contextMs")).isTrue();
        assertThat(body.has("modelMs")).isTrue();
        // 실험 재고 시드가 있으므로 걸러져 빈 목록이 되면 안 된다(시드-풀 표류를 여기서도 잡는다).
        assertThat(body.get("items")).as("시드가 실험 풀을 덮으면 모델 결과가 살아남는다").isNotEmpty();
        // 폴백이어도 200 이다 — 모델을 못 불러도 홈은 살아야 한다(E3 계약).
        assertThat(body.get("source").asText()).isIn("MODEL", "FALLBACK");
    }

    @Test
    @DisplayName("비로그인은 401 — 추천도 본인 활동으로 만든다")
    void anonymousIsRejected() {
        assertThat(rest.getForEntity("/api/v1/recommendations", String.class).getStatusCode().value())
                .isEqualTo(401);
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
