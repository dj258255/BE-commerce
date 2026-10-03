package com.beomsu.becommerce.personalization;

import static org.assertj.core.api.Assertions.assertThat;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * user_map 조회를 <b>실 MySQL</b>로 검증한다(S2, #455).
 *
 * <p>표의 규칙은 하나다 — <b>행이 있으면 매핑, 없으면 빈 값</b>. 매핑 없음을 예외로 만들지 않는다는 것이
 * 이 테스트의 핵심이다. {@code ddl-auto=validate} 로 뜨므로 매핑 엔티티(V66 스키마)도 함께 검증된다.
 */
@Tag("integration")
@SpringBootTest
@DisplayName("user_map 조회 — 행이 있으면 매핑, 없으면 빈 값")
class PersonalizationUserMapFactsIntegrationTest {

    @DynamicPropertySource
    static void datasourceAndRedis(DynamicPropertyRegistry registry) {
        SharedContainers.register(registry, "UserMap");
    }

    @Autowired
    PersonalizationUserMapFacts facts;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("delete from personalization_user_map");
    }

    @Test
    @DisplayName("행이 있으면 그 회원의 hm_customer_id 를 주고, 없으면 빈 값이다")
    void mappingIsReadAndMissingIsEmpty() {
        jdbc.update("insert into personalization_user_map (user_id, hm_customer_id) values (?, ?)", 7L, "0a1b2c3d");

        assertThat(facts.hmCustomerId(7L)).contains("0a1b2c3d");
        assertThat(facts.hmCustomerId(8L)).isEmpty();
    }
}
