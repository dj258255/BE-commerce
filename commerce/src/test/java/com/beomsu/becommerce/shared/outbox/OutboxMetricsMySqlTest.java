package com.beomsu.becommerce.shared.outbox;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OutboxMetrics#oldestPendingAgeSeconds()}가 실 MySQL 드라이버에서 도는지 본다(#393).
 *
 * <p>미소비 이벤트가 없으면 {@code min(publication_date)}가 SQL {@code NULL}이라 통과하지만,
 * <b>있을 때만</b> 드라이버가 {@code DATETIME}을 {@code LocalDateTime}으로 돌려주는데 예전 코드는
 * 이를 {@code Instant.class}로 읽으려다 {@code TypeMismatchDataAccessException}이 났다(#392 측정
 * 중 실 MySQL 8.4에서 발견). 목/H2 단위 테스트로는 드러나지 않는 드라이버 타입 계약이라 실 스키마 +
 * 실 드라이버로 값을 직접 넣어 확인한다.
 */
@Tag("integration")
@SpringBootTest
class OutboxMetricsMySqlTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        SharedContainers.register(r, "OutboxMetrics");
    }

    @Autowired OutboxMetrics metrics;
    @Autowired JdbcTemplate jdbc;

    @Test
    @DisplayName("미소비 이벤트가 없으면 0을 낸다(타입 변환 자체가 없는 경로)")
    void zeroWhenNoPending() {
        assertThat(metrics.pendingCount()).isZero();
        assertThat(metrics.oldestPendingAgeSeconds()).isZero();
    }

    @Test
    @DisplayName("미소비 이벤트가 있으면 나이를 초로 돌려준다 — 예전 코드는 여기서 TypeMismatchDataAccessException이 났다(#393)")
    void ageWhenPendingExists() {
        insertPending("evt-outbox-metrics-test", 60);   // 60초 전 발행, 아직 미소비
        try {
            assertThat(metrics.pendingCount()).isEqualTo(1L);
            // 60초 전에 심었으니 나이는 60초 이상이어야 하고(실행 지연 감안), 터무니없이 크면 안 된다.
            assertThat(metrics.oldestPendingAgeSeconds()).isBetween(59.0, 120.0);
        } finally {
            jdbc.update("delete from event_publication where serialized_event = ?", "evt-outbox-metrics-test");
        }
    }

    private void insertPending(String marker, int secondsAgo) {
        jdbc.update("""
                insert into event_publication (id, publication_date, event_type, listener_id, serialized_event)
                values (unhex(replace(uuid(), '-', '')),
                        date_sub(utc_timestamp(6), interval ? second), 'TestEvent', 'test-listener', ?)
                """, secondsAgo, marker);
    }
}
