package com.beomsu.becommerce.fraud.integration;

import com.beomsu.becommerce.fraud.model.CardTransaction;
import com.beomsu.becommerce.fraud.model.CardTransactionRepository;
import com.beomsu.becommerce.payment.pg.CardFingerprint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카드 지문이 <b>실 스키마에서</b> 결제 여러 건을 한 카드로 묶는지 본다.
 *
 * <p><b>목으로는 안 잡힌다.</b> 창을 채우는 조회는 {@code cardKey} 로 거는 파생 쿼리이고,
 * V46 이 컬럼과 인덱스를 만들지 못하면 그 조회가 뜨는 것은 기동 시점이다. 단위 테스트는
 * 저장소를 목으로 두므로 마이그레이션이 깨져도 초록으로 지나간다.
 *
 * <p><b>왜 이 테스트가 있는가</b>: 지문을 붙이기 전에는 사후 탐지가 이력을 {@code paymentKey}
 * 로 묶었고 그것은 결제마다 새로 발급된다. 창에 늘 이번 건 하나만 들어와서 과거를 보는 피처가
 * 사실상 죽어 있었다. 고쳤다고 말하려면 <b>여러 건이 실제로 한 창에 들어오는 것</b>을 봐야 한다.
 *
 * <p>여기서 재는 것은 <b>묶임뿐이다.</b> 탐지 성적이 좋아지는지는 이 테스트가 답하지 않는다.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest
@DisplayName("카드 지문 — 결제 여러 건이 한 카드의 창으로 묶이는지")
class CardFingerprintGroupingIntegrationTest {

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
    CardTransactionRepository transactions;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("V46 이 결제에 카드 지문 자리를 만든다 — 없으면 지문이 갈 데가 없다")
    void migrationAddsTheColumn() {
        Integer columns = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'payments'
                   AND column_name = 'card_fingerprint'""", Integer.class);

        assertThat(columns).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 결제로 심사가 두 줄 생기지 않는다 — 자기 자신을 지난 심사로 세게 된다")
    void reviewIsUniquePerPayment() {
        Integer constraints = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.table_constraints
                 WHERE table_schema = DATABASE() AND table_name = 'fraud_reviews'
                   AND constraint_name = 'uk_fraud_reviews_payment'
                   AND constraint_type = 'UNIQUE'""", Integer.class);

        assertThat(constraints)
                .as("이벤트가 at-least-once 라 제약이 없으면 같은 결제가 두 줄이 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("같은 지문의 결제 셋이 한 창에 들어온다 — 결제 키로 묶던 때는 하나뿐이었다")
    void samefingerprintGroupsIntoOneWindow() {
        String card = CardFingerprint.of("12345678****123*", "3K");
        String other = CardFingerprint.of("87654321****999*", "3K");
        Instant now = Instant.now();

        transactions.save(CardTransaction.of(card, "CFG-1", 300, 0, null, null, now.minus(Duration.ofHours(3))));
        transactions.save(CardTransaction.of(card, "CFG-2", 500, 0, null, null, now.minus(Duration.ofHours(2))));
        transactions.save(CardTransaction.of(card, "CFG-3", 900_000, 0, null, null, now.minus(Duration.ofHours(1))));
        transactions.save(CardTransaction.of(other, "CFG-4", 700_000, 0, null, null, now.minus(Duration.ofHours(1))));

        List<CardTransaction> window = transactions
                .findByCardKeyAndOccurredAtBetweenOrderByOccurredAtDesc(
                        card, now.minus(Duration.ofHours(24)), now, PageRequest.of(0, 200));

        assertThat(window).hasSize(3);
        assertThat(window).extracting(CardTransaction::getOrderNo)
                .as("최근 것이 먼저 온다")
                .containsExactly("CFG-3", "CFG-2", "CFG-1");
        assertThat(window).extracting(CardTransaction::getCardKey)
                .as("다른 카드가 섞이면 그 카드의 기준선이 아니게 된다")
                .containsOnly(card);
    }

    @Test
    @DisplayName("지문은 64자 해시라 컬럼에 들어간다 — 길이가 넘치면 조용히 잘려 서로 다른 카드가 한 키가 된다")
    void fingerprintFitsTheColumn() {
        Integer length = jdbc.queryForObject("""
                SELECT character_maximum_length FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'payments'
                   AND column_name = 'card_fingerprint'""", Integer.class);

        assertThat(CardFingerprint.of("12345678****123*", "3K")).hasSize(64);
        assertThat(length).isGreaterThanOrEqualTo(64);
    }
}
