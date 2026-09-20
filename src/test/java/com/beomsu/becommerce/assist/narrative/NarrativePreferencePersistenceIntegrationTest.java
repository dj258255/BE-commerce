package com.beomsu.becommerce.assist.narrative;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
 * 쌍 비교 기록이 <b>실 MySQL 에 왕복하는지</b>를 지킨다.
 *
 * <p><b>왜 필요한가</b>: 이 기능의 기존 테스트는 리포지터리까지 목이라, {@code narrative_preferences}
 * 테이블에 행이 한 번도 쓰인 적이 없었다. 기본값을 아직 안 바꿨으므로 운영에서도 안 쓰인다.
 * <b>스키마와 엔티티가 어긋나 있어도 아무도 모르는 상태</b>였다.
 *
 * <p>이 프로젝트에서 이미 한 번 겪은 고장이다. 카멜케이스 필드를 그냥 두면 하이버네이트가
 * {@code sourceA} 를 {@code source_a} 가 아니라 {@code sourcea} 로 매핑한다. 지금은
 * {@code @Column(name=...)} 으로 못 박아 뒀는데, 그 못이 빠져도 목 테스트는 통과한다.
 *
 * <p><b>블라인드가 DB 층에서도 지켜지는지</b>도 여기서 본다. 고르기 전 행에는 {@code choice} 와
 * {@code reviewer} 가 비어 있어야 하고, 고른 뒤에만 채워져야 한다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@DisplayName("쌍 비교 영속 통합 — 기록이 실 MySQL 에 왕복하는지")
class NarrativePreferencePersistenceIntegrationTest {

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
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        registry.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @Autowired
    NarrativePreferenceRepository repository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("저장한 두 서술과 그 자리가 컬럼에 그대로 남는다")
    void roundTripsThroughRealSchema() {
        var saved = repository.save(NarrativePreference.of(
                "ORD-PERSIST-1", "template", "템플릿이 쓴 문단", "ollama:qwen3:8b", "모델이 쓴 문단"));

        // 엔티티가 아니라 SQL 로 다시 읽는다. 하이버네이트가 어디에 썼는지를 확인해야 한다.
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT order_no, source_a, source_b, text_a, text_b, choice, reviewer "
                        + "FROM narrative_preferences WHERE id = ?", saved.getId());

        assertThat(row.get("order_no")).isEqualTo("ORD-PERSIST-1");
        assertThat(row.get("source_a")).isEqualTo("template");
        assertThat(row.get("source_b")).isEqualTo("ollama:qwen3:8b");
        assertThat(row.get("text_a")).isEqualTo("템플릿이 쓴 문단");
        assertThat(row.get("text_b")).isEqualTo("모델이 쓴 문단");
        // 고르기 전에는 비어 있어야 한다.
        assertThat(row.get("choice")).isNull();
        assertThat(row.get("reviewer")).isNull();
    }

    @Test
    @DisplayName("고른 뒤에야 choice 와 reviewer 가 채워진다")
    void recordsChoiceOnlyAfterChoosing() {
        var saved = repository.save(NarrativePreference.of(
                "ORD-PERSIST-2", "ollama:qwen3:8b", "모델이 쓴 문단", "template", "템플릿이 쓴 문단"));

        saved.choose("A", "judge:claude");
        repository.saveAndFlush(saved);

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT choice, reviewer, chosen_at, source_a FROM narrative_preferences WHERE id = ?",
                saved.getId());

        assertThat(row.get("choice")).isEqualTo("A");
        // 사람이 아닌 심판은 이름으로 구분한다. 승격 기준은 사람이 고른 표본이다.
        assertThat(row.get("reviewer")).isEqualTo("judge:claude");
        assertThat(row.get("chosen_at")).isNotNull();
        assertThat(row.get("source_a")).isEqualTo("ollama:qwen3:8b");
    }
}
