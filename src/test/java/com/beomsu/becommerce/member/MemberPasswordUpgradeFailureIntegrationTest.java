package com.beomsu.becommerce.member;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 이관 쓰기가 <b>실패해도 로그인이 막히지 않는지</b>를 실 MySQL 로 확인한다(ADR-009).
 *
 * <p><b>왜 목이 아니라 실 DB 인가</b>: 이 동작의 핵심은 트랜잭션 경계다. 같은 트랜잭션에서 예외를
 * 잡아도 그 트랜잭션은 rollback-only 로 낙인찍혀 커밋이 실패한다(pay-26 교훈). 목 리포지토리로는
 * 그 사실이 드러나지 않는다. 그래서 저장이 실제로 실패하는 조건을 만든다 — 컬럼(varchar(255))보다
 * 긴 해시를 넘긴다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@DisplayName("이관 실패가 로그인을 막지 않는다 — 실 MySQL")
class MemberPasswordUpgradeFailureIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.4"))
            .withDatabaseName("becommerce").withUsername("becommerce").withPassword("becommerce");

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        props.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + "?serverTimezone=UTC&characterEncoding=UTF-8");
        props.add("spring.datasource.username", MYSQL::getUsername);
        props.add("spring.datasource.password", MYSQL::getPassword);
        props.add("spring.data.redis.host", REDIS::getHost);
        props.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
        props.add("spring.kafka.bootstrap-servers", () -> "");
    }

    @Autowired
    MemberPasswordUpgradeService upgrades;

    @Autowired
    MemberRepository members;

    @Autowired
    MeterRegistry registry;

    @Test
    @DisplayName("저장이 실패해도 인증은 통과하고, 해시는 롤백되며, 실패 카운터가 오른다")
    void survivesRealDatabaseWriteFailure() {
        Member member = members.save(Member.of("upgrade-fail@test.local", "{bcrypt}$2a$10$old"));
        String tooLongForColumn = "{argon2}" + "x".repeat(300);   // password_hash 는 varchar(255)
        UserDetails user = User.withUsername(String.valueOf(member.getId()))
                .password(member.getPasswordHash())
                .roles("USER")
                .build();

        double failedBefore = registry.get("password.hash.upgrade.failed").counter().count();

        UserDetails result = upgrades.updatePassword(user, tooLongForColumn);

        assertThat(result).as("이관이 실패해도 인증은 그대로 통과해야 한다").isSameAs(user);
        assertThat(members.findById(member.getId()).orElseThrow().getPasswordHash())
                .as("저장이 실패했으니 해시는 옛 값 그대로여야 한다")
                .isEqualTo("{bcrypt}$2a$10$old");
        assertThat(registry.get("password.hash.upgrade.failed").counter().count() - failedBefore)
                .as("실패는 조용히 지나가지 않는다")
                .isEqualTo(1.0);
    }
}
