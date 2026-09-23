package com.beomsu.becommerce.order;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;

import java.sql.*;
import java.util.*;

/**
 * 마이그레이션을 실제로 다 돌린 뒤, <b>조회가 거는 조건 컬럼에 인덱스가 있는지</b>를 실 스키마에 묻는다.
 *
 * <p>소스에서 정규식으로 세는 방식은 두 방향으로 틀렸다. JPA가 유니크 제약으로 만들어 준 인덱스를
 * 못 보고(있는데 없다고 함), 파생 메서드 이름 파싱이 조건 없는 {@code findAllByOrderBy...}까지
 * 조건으로 셌다(없는데 있다고 함). 그래서 <b>스키마를 만들어 놓고 물어본다.</b>
 *
 * <p>이 테스트는 재는 것이 아니라 <b>고정하는</b> 것이다. 조회 조건에 쓰는 컬럼을 나중에 누가 추가하면
 * 여기서 걸린다.
 */
@Tag("integration")
class SchemaIndexAuditMySqlTest {

    /**
     * 인덱스 선두에 있어야 하는 (테이블, 컬럼). 소스 스윕 결과를 사람이 확인해 옮겼다.
     *
     * <p>일부러 뺀 것: {@code audit_logs.target_type}(쓰기가 많고 조회는 사후 조사뿐),
     * {@code blind_reviews.edited_at}·{@code narrative_preferences.choice}(평가용, 수백 행),
     * {@code ledger_transactions.source_type}(어드민 최근 50건뿐). 근거는 V38 에 적었다.
     */
    private static final String[][] LOOKUPS = {
            {"orders", "user_id"}, {"payments", "payment_key"}, {"disputes", "order_no"},
            {"disputes", "status"}, {"cash_receipts", "order_no"}, 
             {"dunning_attempts", "subscription_id"},
            
            
            {"point_histories", "user_id"}, {"point_histories", "order_no"},
            {"reconciliation_results", "order_no"}, {"reconciliation_results", "status"},
            {"settlement_items", "order_no"}, {"settlement_items", "status"},
            {"subscriptions", "user_id"}, {"subscriptions", "status"}, {"subscriptions", "billing_key"},
            {"virtual_accounts", "payment_key"}, {"virtual_accounts", "status"},
            {"wallet_transactions", "user_id"},
            // 찜은 유니크 키 (user_id, product_id)의 선두 컬럼이 user_id라 별도 인덱스를 두지 않는다(V58).
            {"wishlist_items", "user_id"},
            // 개인화 활동 로그도 유니크 키 (user_id, seq)의 선두 컬럼이 user_id다(V59).
            {"user_activities", "user_id"},
    };

    @Test
    @DisplayName("조회 조건 컬럼이 인덱스 선두에 있는지 실 스키마에 묻는다")
    void audit() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("audit")) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").load().migrate();

            Map<String, Set<String>> firstCols = new HashMap<>();
            Set<String> tables = new HashSet<>();
            try (Connection c = mysql.createConnection("");
                 Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery(
                         "SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.STATISTICS " +
                         "WHERE TABLE_SCHEMA = DATABASE() AND SEQ_IN_INDEX = 1")) {
                while (rs.next()) {
                    firstCols.computeIfAbsent(rs.getString(1), k -> new HashSet<>()).add(rs.getString(2));
                    tables.add(rs.getString(1));
                }
            }

            List<String> missing = new ArrayList<>();
            for (String[] l : LOOKUPS) {
                if (!tables.contains(l[0])) continue;
                if (!firstCols.getOrDefault(l[0], Set.of()).contains(l[1])) missing.add(l[0] + "." + l[1]);
            }

            System.out.printf("%n=== 실 스키마 인덱스 감사 (테이블 %d개) ===%n", tables.size());
            System.out.printf("인덱스가 있어야 하는 조회 조건 %d개 중 없는 것 %d개%n", LOOKUPS.length, missing.size());
            missing.forEach(m -> System.out.println("  없음  " + m));

            org.assertj.core.api.Assertions.assertThat(missing)
                    .describedAs("조회 조건 컬럼이 인덱스 선두에 없다 — V38 에 추가하거나, 안 거는 근거를 EXCLUDED 에 적어라")
                    .isEmpty();
        }
    }
}
