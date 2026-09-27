package com.beomsu.becommerce.reconciliation.integrity;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정합성 불변식(#389)의 SQL 이 Flyway 가 만든 실제 스키마에서 도는지 본다.
 *
 * <p>불변식은 모듈 여러 개의 테이블을 SQL 로 직접 잇는다. 컬럼 이름이나 ENUM 값이 바뀌면 컴파일은 되고 점검만 조용히 실패한다.
 * 점검이 실패하면 게이지는 직전 값에 머무르므로 여기서 먼저 잡는다. 불변식마다 잡는지는 {@code tools/run-integrity.sh} 가
 * 실제 부하 뒤 데이터에 오염을 넣어 확인한다.
 */
@Tag("integration")
@SpringBootTest
class IntegrityCheckMySqlTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        SharedContainers.register(r, "IntegrityCheck");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    @Autowired IntegrityCheckService service;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("불변식 10개가 실제 스키마에서 돌고 빈 DB 에서는 모두 0 이다")
    void allInvariantsRunOnMigratedSchema() {
        Map<Invariant, Long> counts = service.count(Duration.ZERO);

        assertThat(counts).hasSize(Invariant.values().length);
        assertThat(counts.values()).allMatch(n -> n == 0L);
        for (Invariant inv : Invariant.values()) {
            assertThat(service.samples(inv, Duration.ZERO, 3)).isEmpty();
        }
    }

    @Test
    @DisplayName("유예보다 최근에 승인된 결제는 분개가 없어도 세지 않고 유예가 지나면 센다")
    void graceSkipsRecentApprovals() {
        jdbc.update("""
                INSERT INTO payments (order_no, payment_key, amount, balance_amount, status, requested_at, approved_at,
                                      version, cancel_count, recovery_attempts, installment_months, pg_provider)
                VALUES ('ORD-GRACE-1', 'pk-grace-1', 10000, 10000, 'DONE', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6), 0, 0, 0, 0, 'FAKE')""");
        try {
            assertThat(service.count(Duration.ofSeconds(60)).get(Invariant.APPROVED_WITHOUT_LEDGER)).isZero();
            assertThat(service.count(Duration.ofSeconds(-5)).get(Invariant.APPROVED_WITHOUT_LEDGER)).isEqualTo(1L);
            assertThat(service.samples(Invariant.APPROVED_WITHOUT_LEDGER, Duration.ofSeconds(-5), 3)).containsExactly("ORD-GRACE-1");
        } finally {
            jdbc.update("DELETE FROM payments WHERE order_no = 'ORD-GRACE-1'");
        }
    }
}
