package com.beomsu.becommerce.settlement;

import com.beomsu.becommerce.settlement.internal.SettlementService;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 지각 도착이 실제로 얼마나 생기는지, <b>가정한 트래픽</b>으로 돌려 본다(ADR-023).
 *
 * <p><b>이것은 실측이 아니다.</b> 이 저장소에는 실 트래픽이 없다. 아래 수치는 여기 적은
 * 시나리오 입력의 결과이고, 입력을 바꾸면 값도 바뀐다. 알고 싶은 것은 "우리 서비스의
 * 지각률이 몇 퍼센트인가"가 아니라 <b>"지각은 무엇의 함수인가"</b> 다. 그건 트래픽 없이도
 * 답할 수 있고, 답이 나오면 B 안(귀속일 분리)을 고를지도 정해진다.
 *
 * <p>시나리오 세 갈래를 같은 코드로 돌린다.
 * <ul>
 *   <li><b>정상 운영</b> — 매일 확정되고 매일 배치가 돈다</li>
 *   <li><b>배치 하루 실패</b> — 하루를 건너뛰고 다음 날 복구한다</li>
 *   <li><b>물량이 틱 상한 초과</b> — 한 틱이 다 못 비운다</li>
 * </ul>
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(properties = {
        // 틱당 상한을 작게 잡아 <상한이 걸리는 날>을 짧은 시나리오로 재현한다.
        "app.batch.read-chunk-size=50",
        "app.batch.settlement-max-pages=2",
        "app.settlement.enabled=false"   // 스케줄러는 끄고 이 테스트가 직접 부른다
})
@DisplayName("지각 도착은 트래픽이 아니라 배치 건강도의 함수인가")
class SettlementLatenessScenarioTest {

    /** 한 틱이 비울 수 있는 양. read-chunk-size × settlement-max-pages. */
    private static final int TICK_CAPACITY = 50 * 2;

    private static final long PLATFORM = 1L;
    private static final LocalDate DAY0 = LocalDate.of(2026, 7, 27);

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
    SettlementService settlementService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("정상 운영에서는 지각이 0일이다 — 확정일이 곧 집계일이다")
    void healthyOperationHasNoLateness() {
        reset();
        // 열흘 동안 매일 30건이 확정되고, 매일 그날 배치가 돈다.
        for (int day = 0; day < 10; day++) {
            LocalDate d = DAY0.plusDays(day);
            confirmItems(30, d);
            settlementService.settle(d);
        }

        Map<Long, Long> byLateness = latenessHistogram();
        print("정상 운영(매일 30건, 매일 배치)", byLateness);

        assertThat(byLateness.keySet())
                .as("확정일에 그날 배치가 돌면 집계일과 확정일이 같다. 지각은 구조적으로 안 생긴다")
                .containsExactly(0L);
        assertThat(byLateness.get(0L)).isEqualTo(300L);
    }

    @Test
    @DisplayName("배치가 하루 실패하면 그 하루치가 통째로 하루 늦는다")
    void skippedBatchShiftsAWholeDay() {
        reset();
        for (int day = 0; day < 6; day++) {
            LocalDate d = DAY0.plusDays(day);
            confirmItems(30, d);
            if (day == 3) {
                continue;   // 이 날 배치가 실패했다. 다음 날 복구한다
            }
            settlementService.settle(d);
        }

        Map<Long, Long> byLateness = latenessHistogram();
        print("배치 하루 실패(4일째 건너뜀)", byLateness);

        assertThat(byLateness.get(1L))
                .as("건너뛴 날의 30건이 다음 날 배치에 하루 늦게 딸려 들어간다")
                .isEqualTo(30L);
        assertThat(byLateness.get(0L)).isEqualTo(150L);
    }

    @Test
    @DisplayName("물량이 틱 상한을 넘으면 넘친 만큼이 계속 밀린다 — 지각이 누적된다")
    void volumeAboveTickCapacityAccumulates() {
        reset();
        // 틱이 비울 수 있는 양보다 매일 50건씩 더 들어온다.
        int perDay = TICK_CAPACITY + 50;
        for (int day = 0; day < 5; day++) {
            LocalDate d = DAY0.plusDays(day);
            confirmItems(perDay, d);
            settlementService.settle(d);
        }

        Map<Long, Long> byLateness = latenessHistogram();
        print("물량 초과(틱 상한 " + TICK_CAPACITY + ", 매일 " + perDay + "건)", byLateness);

        assertThat(byLateness.keySet())
                .as("하루 이상 늦은 항목이 생긴다")
                .anyMatch(days -> days > 0);
        Long stillPending = jdbc.queryForObject(
                "SELECT COUNT(*) FROM settlement_items WHERE status = 'CONFIRMED'", Long.class);
        assertThat(stillPending)
                .as("틱이 다 못 비운 재고가 남는다. 이것이 계속 쌓이면 지각 일수가 커진다")
                .isGreaterThan(0L);
    }

    // --- 시나리오 도구 -------------------------------------------------------

    /** 확정 상태의 정산 항목 n 건을 그 날짜로 적재한다. 승인에서 구매확정까지는 이미 지났다고 본다. */
    private void confirmItems(int n, LocalDate confirmedDate) {
        Long maxId = jdbc.queryForObject("SELECT COALESCE(MAX(payment_id), 0) FROM settlement_items", Long.class);
        long from = maxId + 1;
        for (int i = 0; i < n; i++) {
            long paymentId = from + i;
            jdbc.update("""
                    INSERT INTO settlement_items
                      (payment_id, order_no, amount, confirmed_date, status, seller_id, last_cancel_seq)
                    VALUES (?, ?, 10000, ?, 'CONFIRMED', ?, -1)
                    """, paymentId, "sc-" + paymentId, confirmedDate, PLATFORM);
        }
    }

    /** 집계일과 확정일의 차이별 항목 수. 이 표가 이 테스트의 결과물이다. */
    private Map<Long, Long> latenessHistogram() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT DATEDIFF(s.settlement_date, i.confirmed_date) AS days_late, COUNT(*) AS cnt
                FROM settlement_items i
                JOIN settlements s ON s.id = i.settlement_id
                WHERE i.status = 'SETTLED'
                GROUP BY days_late
                ORDER BY days_late
                """);
        return rows.stream().collect(java.util.stream.Collectors.toMap(
                r -> ((Number) r.get("days_late")).longValue(),
                r -> ((Number) r.get("cnt")).longValue(),
                (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private void print(String label, Map<Long, Long> histogram) {
        long total = histogram.values().stream().mapToLong(Long::longValue).sum();
        long late = histogram.entrySet().stream()
                .filter(e -> e.getKey() > 0).mapToLong(Map.Entry::getValue).sum();
        System.out.printf("%n[시나리오] %s%n", label);
        histogram.forEach((days, cnt) -> System.out.printf("  %d일 늦음: %d건%n", days, cnt));
        System.out.printf("  정산된 %d건 중 지각 %d건 (%.1f%%)%n",
                total, late, total == 0 ? 0.0 : (100.0 * late / total));
    }

    private void reset() {
        jdbc.update("DELETE FROM settlement_items");
        jdbc.update("DELETE FROM settlements");
    }
}
