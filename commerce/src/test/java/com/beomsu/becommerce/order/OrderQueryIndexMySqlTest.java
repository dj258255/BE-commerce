package com.beomsu.becommerce.order;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 내 주문 목록 조회({@code GET /api/v1/orders})를 <b>실제 MySQL(InnoDB)</b>에서 잰다.
 *
 * <p><b>왜 쟀나</b><br>
 * 배치의 무제한 조회를 잡고 나서 서빙 쪽은 안 봤다는 것을 알았다. 보니 목록 조회는
 * {@code findTop50ByUserIdOrderByIdDesc} 라 건수는 이미 묶여 있었다. 문제는 건수가 아니라
 * <b>그 50건을 어떻게 찾느냐</b>였다. {@code orders} 에 있는 인덱스는 {@code (status, created_at)}
 * 과 PK뿐이고 <b>{@code user_id} 인덱스가 없다.</b>
 *
 * <p><b>가설</b><br>
 * {@code WHERE user_id=? ORDER BY id DESC LIMIT 50} 은 인덱스가 없으면 PK를 역방향으로 훑으며
 * 걸러낸다. 그 사용자의 주문이 50건 이상이면 위쪽에서 금방 채우고 멈춘다. 그런데
 * <b>주문이 50건 미만인 사용자는 그 50건을 영영 못 채워</b> 테이블 끝까지 간다. 그리고 실제
 * 서비스에서 주문 50건 미만은 예외가 아니라 <b>대다수</b>다 — 흔한 경우가 곧 최악의 경우다.
 *
 * <p><b>측정 설계</b><br>
 * 사용자를 라운드로빈으로 섞어 넣어 한 사용자의 주문이 id 전 구간에 흩어지게 한다(현실의 시간
 * 순서와 같다). 같은 쿼리를 인덱스 추가 전후로 재고, 워밍업 뒤 중앙값을 쓴다. 버퍼 풀에 다 올라간
 * 뒤를 재므로 <b>디스크 I/O가 아니라 스캔한 행 수 자체의 비용</b>이다. 즉 이 수치는 낙관적인
 * 쪽이고, 콜드 상태의 실제 격차는 더 크다.
 *
 * <p>기본 스위트에서 제외한다(Docker 필요). {@code ./gradlew measurementTest}로 실행(#288).
 */
@Tag("measurement")   // 30만 행을 채워 인덱스 효과를 재는 실험 — 매일 돈다(#288). 인덱스 존재는 SchemaIndexAuditMySqlTest 가 PR 마다 지킨다
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OrderQueryIndexMySqlTest {

    private static final int USERS = 10_000;
    private static final int ORDERS_PER_USER = 30;      // 50 미만 — LIMIT 50 을 못 채우는 흔한 사용자
    private static final int TOTAL = USERS * ORDERS_PER_USER;
    private static final int ROUNDS = 7;
    private static final int WARMUP = 3;

    private static final String QUERY =
            "SELECT id, order_no, status, total_amount, currency, created_at " +
            "FROM orders WHERE user_id = ? ORDER BY id DESC LIMIT 50";

    private static String jdbcUrl;
    private static HikariDataSource ds;
    private static double beforeMedian;

    @BeforeAll
    static void startDb() throws SQLException {
        // 공용 MySQL(#276)에 새 DB. 버퍼 풀 512MB 는 공용 컨테이너가 준다(max-connections 는 100 대신 400)
        jdbcUrl = SharedContainers.freshDatabase("querytest");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(SharedContainers.username());
        cfg.setPassword(SharedContainers.password());
        cfg.setMaximumPoolSize(4);
        ds = new HikariDataSource(cfg);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            // 운영 스키마와 같은 인덱스만 만든다(V1__init.sql). user_id 인덱스는 일부러 없다.
            s.execute("""
                CREATE TABLE orders (
                  id BIGINT NOT NULL AUTO_INCREMENT,
                  user_id BIGINT NOT NULL,
                  order_no VARCHAR(64) NOT NULL,
                  status ENUM('CANCELED','CREATED','EXPIRED','FAILED','PAID','PAYMENT_IN_PROGRESS','PENDING_PAYMENT') NOT NULL,
                  total_amount BIGINT NOT NULL,
                  currency VARCHAR(3) NOT NULL,
                  version BIGINT NOT NULL,
                  created_at DATETIME(6) NOT NULL,
                  updated_at DATETIME(6) NOT NULL,
                  expires_at DATETIME(6) NOT NULL,
                  PRIMARY KEY (id)
                ) ENGINE=InnoDB""");
            s.execute("CREATE INDEX idx_orders_status_created ON orders (status, created_at)");
        }
        seed();
    }

    /** 라운드로빈으로 넣어 한 사용자의 주문이 id 전 구간에 흩어지게 한다. */
    private static void seed() throws SQLException {
        long t0 = System.currentTimeMillis();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO orders (user_id, order_no, status, total_amount, currency, version," +
                    " created_at, updated_at, expires_at) VALUES (?,?,'PAID',10000,'KRW',0,NOW(6),NOW(6),NOW(6))")) {
                int n = 0;
                for (int round = 0; round < ORDERS_PER_USER; round++) {
                    for (int u = 1; u <= USERS; u++) {
                        ps.setLong(1, u);
                        ps.setString(2, "ORD-" + round + "-" + u);
                        ps.addBatch();
                        if (++n % 10_000 == 0) { ps.executeBatch(); c.commit(); }
                    }
                }
                ps.executeBatch();
                c.commit();
            }
        }
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("ANALYZE TABLE orders");
        }
        System.out.printf("%n=== 실 MySQL 8.4 · InnoDB · orders %,d행 (사용자 %,d × %d건) · 적재 %.1fs ===%n",
                TOTAL, USERS, ORDERS_PER_USER, (System.currentTimeMillis() - t0) / 1000.0);
    }

    @AfterAll
    static void stopDb() {
        if (ds != null) ds.close();
    }

    @Test
    @Order(1)
    @DisplayName("As-is: user_id 인덱스 없이 내 주문 50건 조회")
    void beforeIndex() throws Exception {
        System.out.println("\n--- As-is (인덱스 없음) ---");
        explain();
        beforeMedian = measure();
        assertThat(rowCount()).isEqualTo(ORDERS_PER_USER);   // 30건뿐이라 LIMIT 50 을 못 채운다
    }

    @Test
    @Order(2)
    @DisplayName("To-be: (user_id, id) 인덱스를 걸고 같은 조회")
    void afterIndex() throws Exception {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            long t0 = System.currentTimeMillis();
            s.execute("CREATE INDEX idx_orders_user_id_id ON orders (user_id, id)");
            s.execute("ANALYZE TABLE orders");
            System.out.printf("%n인덱스 생성 %.1fs%n", (System.currentTimeMillis() - t0) / 1000.0);
        }
        System.out.println("\n--- To-be ((user_id, id) 인덱스) ---");
        explain();
        double after = measure();

        System.out.printf("%n>>> As-is %.1fms → To-be %.1fms (%.0f배)%n", beforeMedian, after, beforeMedian / after);
        assertThat(after).isLessThan(beforeMedian);
    }

    /** 실행 계획과 실제로 훑은 행 수를 남긴다 — 느린 이유가 스캔 폭임을 보이기 위해. */
    private void explain() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("EXPLAIN " + QUERY)) {
            ps.setLong(1, 7);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    System.out.printf("  EXPLAIN  type=%s  key=%s  rows=%s  Extra=%s%n",
                            rs.getString("type"), rs.getString("key"), rs.getString("rows"), rs.getString("Extra"));
                }
            }
        }
        // EXPLAIN 은 추정이라 이 문제를 못 잡는다 — 실제로 몇 행을 훑었는지는 EXPLAIN ANALYZE 로만 보인다.
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement("EXPLAIN ANALYZE " + QUERY)) {
            ps.setLong(1, 7);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    for (String line : rs.getString(1).split("\n")) System.out.println("  " + line.strip());
                }
            }
        }
    }

    /** 사용자를 매번 바꿔 결과 캐시나 한 사용자에 치우친 적재를 피한다. */
    private double measure() throws SQLException {
        List<Double> ms = new ArrayList<>();
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(QUERY)) {
            for (int i = 0; i < WARMUP + ROUNDS; i++) {
                ps.setLong(1, (i % USERS) + 1);
                long t0 = System.nanoTime();
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) { /* 소비 */ } }
                double took = (System.nanoTime() - t0) / 1_000_000.0;
                if (i >= WARMUP) { ms.add(took); System.out.printf("  %d회차 %.1fms%n", i - WARMUP + 1, took); }
            }
        }
        ms.sort(Double::compare);
        double median = ms.get(ms.size() / 2);
        System.out.printf("  중앙값 %.1fms%n", median);
        return median;
    }

    private int rowCount() throws SQLException {
        try (Connection c = ds.getConnection(); PreparedStatement ps = c.prepareStatement(QUERY)) {
            ps.setLong(1, 7);
            try (ResultSet rs = ps.executeQuery()) { int n = 0; while (rs.next()) n++; return n; }
        }
    }
}
