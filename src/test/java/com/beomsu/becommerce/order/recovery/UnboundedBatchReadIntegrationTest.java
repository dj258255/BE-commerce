package com.beomsu.becommerce.order.recovery;

import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderStatus;
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

import org.springframework.data.domain.PageRequest;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 조회가 <b>몇 건을 한 번에 읽는지</b>를 실 MySQL 로 재 둔다.
 *
 * <p><b>왜 재나</b>: 배치들이 {@code List} 를 반환하는 조회를 쓰고 있다. 대상이 몇 건이든
 * 한 번에 전부 메모리에 올린다. 평소에는 하루치라 작지만, <b>배치가 며칠 밀리거나 물량이
 * 튀면 그만큼 커진다.</b> 그때 죽는 것은 돈을 다루는 배치다.
 *
 * <p>이 테스트는 그 사실을 <b>숫자로 고정</b>한다. 고치기 전에는 넣은 만큼 다 읽고,
 * 고친 뒤에는 상한까지만 읽어야 한다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@DisplayName("배치 조회 상한 — 밀린 물량을 한 번에 다 읽지 않는가")
class UnboundedBatchReadIntegrationTest {

    /** 밀린 상황을 흉내낸다. 하루치가 아니라 며칠치가 쌓인 것이다. */
    private static final int BACKLOG = 500;

    /** 한 번에 읽을 상한. 실제 기본값(500)보다 작게 잡아 <b>상한이 실제로 걸리는지</b> 본다. */
    private static final int CHUNK = 100;

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
    OrderRepository orderRepository;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("밀린 물량이 상한보다 많아도 한 번에 상한까지만 읽는다")
    void readsAtMostTheChunkSize() {
        Instant past = Instant.now().minus(2, ChronoUnit.DAYS);
        for (int i = 0; i < BACKLOG; i++) {
            jdbc.update("""
                    INSERT INTO orders (order_no, user_id, total_amount, currency, status, version,
                                        created_at, updated_at, expires_at)
                    VALUES (?, 1, 10000, 'KRW', 'PENDING_PAYMENT', 0, ?, ?, ?)
                    """, "ORD-BACKLOG-" + i, past, past, past);
        }

        int read = orderRepository.findByStatusAndExpiresAtBefore(
                OrderStatus.PENDING_PAYMENT, Instant.now(), PageRequest.of(0, CHUNK)).size();

        System.out.printf("%n  쌓인 대상 %d건 → 한 번의 조회가 읽은 행 %d건 (상한 %d)%n",
                BACKLOG, read, CHUNK);
        assertThat(read)
                .as("상한을 넘겨 읽으면 밀린 물량이 그대로 메모리에 올라간다")
                .isLessThanOrEqualTo(CHUNK);
        assertThat(read).as("상한까지는 채워 읽어야 배치가 진도를 낸다").isEqualTo(CHUNK);
    }
}
