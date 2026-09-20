package com.beomsu.becommerce.order;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 카디널리티가 낮은 {@code status} 컬럼에 인덱스를 거는 것이 값을 하는지 <b>실 MySQL</b>에서 잰다.
 *
 * <p><b>왜 재나</b><br>
 * "값이 몇 개뿐인 컬럼은 인덱스를 걸어도 소용없다"가 통념이다. 선택도가 낮아 옵티마이저가 어차피
 * 풀스캔을 고르기 때문이다. 그런데 <b>배치가 거는 조회는 그 통념의 반대편에 있다.</b>
 * {@code WHERE status='PENDING'} 인데 쌓인 행의 대부분은 이미 {@code DONE} 이다. 값의 <b>가짓수</b>는
 * 적지만 찾는 값의 <b>비율</b>이 낮아, 실제 선택도는 오히려 높다.
 *
 * <p>그래서 두 경우를 갈라 잰다. 흔한 값을 찾을 때(50%)와 드문 값을 찾을 때(0.1%)다.
 * 인덱스를 거는 판단이 컬럼의 성질이 아니라 <b>조회가 무엇을 찾는지</b>에 달렸음을 확인한다.
 */
@Tag("integration")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StatusIndexSelectivityMySqlTest {

    private static final int TOTAL = 300_000;
    private static final int PENDING = 300;          // 0.1% — 배치가 매번 찾는 드문 값
    private static final int ROUNDS = 7, WARMUP = 3;

    private static final String RARE   = "SELECT id, order_no FROM settlement_items WHERE status='PENDING' ORDER BY id LIMIT 100";
    private static final String COMMON = "SELECT id, order_no FROM settlement_items WHERE status='DONE'    ORDER BY id LIMIT 100";

    private static MySQLContainer<?> mysql;
    private static HikariDataSource ds;
    private static double rareBefore, commonBefore;

    @BeforeAll
    static void startDb() throws SQLException {
        mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("sel")
                .withCommand("--innodb-buffer-pool-size=536870912");
        mysql.start();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(mysql.getJdbcUrl()); cfg.setUsername(mysql.getUsername()); cfg.setPassword(mysql.getPassword());
        cfg.setMaximumPoolSize(4);
        ds = new HikariDataSource(cfg);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("""
                CREATE TABLE settlement_items (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  order_no VARCHAR(64) NOT NULL,
                  status ENUM('PENDING','DONE','FAILED') NOT NULL,
                  amount BIGINT NOT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB""");
        }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO settlement_items (order_no, status, amount) VALUES (?,?,10000)")) {
                for (int i = 1; i <= TOTAL; i++) {
                    ps.setString(1, "ORD-" + i);
                    // PENDING 은 뒤쪽에 몰리지 않게 전 구간에 흩뿌린다 — 실제로도 처리되다 남은 것들이다.
                    ps.setString(2, (i % (TOTAL / PENDING) == 0) ? "PENDING" : "DONE");
                    ps.addBatch();
                    if (i % 10_000 == 0) { ps.executeBatch(); c.commit(); }
                }
                ps.executeBatch(); c.commit();
            }
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) { s.execute("ANALYZE TABLE settlement_items"); }
        System.out.printf("%n=== settlement_items %,d행 · PENDING %,d행(%.1f%%) ===%n", TOTAL, PENDING, 100.0 * PENDING / TOTAL);
    }

    @AfterAll
    static void stopDb() { if (ds != null) ds.close(); if (mysql != null) mysql.stop(); }

    @Test @Order(1) @DisplayName("As-is: status 인덱스 없이 드문 값(0.1%)과 흔한 값(99.9%)")
    void before() throws Exception {
        System.out.println("\n--- As-is (인덱스 없음) ---");
        System.out.println("  [드문 값 PENDING 0.1%]");  explain(RARE);   rareBefore   = measure(RARE);
        System.out.println("  [흔한 값 DONE 99.9%]");    explain(COMMON); commonBefore = measure(COMMON);
    }

    @Test @Order(2) @DisplayName("To-be: (status, id) 인덱스를 걸면 둘 중 무엇이 좋아지나")
    void after() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE INDEX idx_settlement_item_status_id ON settlement_items (status, id)");
            s.execute("ANALYZE TABLE settlement_items");
        }
        System.out.println("\n--- To-be ((status, id) 인덱스) ---");
        System.out.println("  [드문 값 PENDING 0.1%]");  explain(RARE);   double rare   = measure(RARE);
        System.out.println("  [흔한 값 DONE 99.9%]");    explain(COMMON); double common = measure(COMMON);

        System.out.printf("%n>>> 드문 값  %.1fms → %.1fms (%.0f배)%n", rareBefore, rare, rareBefore / rare);
        System.out.printf(">>> 흔한 값  %.1fms → %.1fms (%.1f배)%n", commonBefore, common, commonBefore / common);
        assertThat(rare).isLessThan(rareBefore);
    }

    private void explain(String q) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("EXPLAIN ANALYZE " + q)) {
            while (rs.next()) for (String line : rs.getString(1).split("\n")) System.out.println("    " + line.strip());
        }
    }

    private double measure(String q) throws SQLException {
        List<Double> ms = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(q)) {
            for (int i = 0; i < WARMUP + ROUNDS; i++) {
                long t0 = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { } }
                if (i >= WARMUP) ms.add((System.nanoTime() - t0) / 1_000_000.0);
            }
        }
        ms.sort(Double::compare);
        double median = ms.get(ms.size() / 2);
        System.out.printf("    중앙값 %.1fms%n", median);
        return median;
    }
}
