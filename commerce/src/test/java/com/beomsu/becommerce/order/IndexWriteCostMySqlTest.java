package com.beomsu.becommerce.order;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;

import java.sql.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인덱스를 걸고 나서 <b>쓰기가 얼마나 느려지는지</b>를 잰다.
 *
 * <p>조회가 107배 빨라졌다는 수치만 적으면 인덱스가 공짜라고 주장하는 셈이다. 인덱스는 조회에서
 * 버는 것을 쓰기에서 갚는 구조물이고, 결제는 쓰기가 많은 시스템이다. 그래서 갚는 쪽도 잰다.
 *
 * <p>{@code orders} 에 같은 행을 넣되 인덱스 유무만 바꾼다. 걸린 인덱스는 실제로 추가한 두 개
 * ({@code (user_id, id)}, {@code (status, expires_at)}) 다.
 */
@Tag("measurement")   // 수치를 재는 측정이다 — PR 마다가 아니라 매일 돈다(#274)
class IndexWriteCostMySqlTest {

    private static final int ROWS = 100_000;
    private static final int BATCH = 1_000;
    private static final int ROUNDS = 5;   // 1회만 재면 몇 %대 차이는 노이즈와 구별되지 않는다

    private static final String DDL = """
            CREATE TABLE %s (
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
            ) ENGINE=InnoDB""";

    @Test
    @DisplayName("인덱스 2개를 더하면 쓰기가 얼마나 느려지나")
    void writeCost() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("wcost")
                .withCommand("--innodb-buffer-pool-size=536870912", "--innodb-flush-log-at-trx-commit=1")) {
            mysql.start();
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(mysql.getJdbcUrl()); cfg.setUsername(mysql.getUsername()); cfg.setPassword(mysql.getPassword());
            cfg.setMaximumPoolSize(2);
            try (HikariDataSource ds = new HikariDataSource(cfg)) {
                try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                    s.execute(DDL.formatted("orders_bare"));
                    s.execute(DDL.formatted("orders_indexed"));
                    s.execute("CREATE INDEX idx_orders_status_created ON orders_bare (status, created_at)");
                    s.execute("CREATE INDEX idx_orders_status_created2 ON orders_indexed (status, created_at)");
                    // V38 이 더한 것
                    s.execute("CREATE INDEX idx_orders_user_id ON orders_indexed (user_id, id)");
                    s.execute("CREATE INDEX idx_orders_status_expires ON orders_indexed (status, expires_at)");
                }
                // 워밍업 — 첫 회차는 버퍼 풀·redo 파일이 덥혀지는 중이라 양쪽 다 느리다.
                insert(ds, "orders_bare"); insert(ds, "orders_indexed");

                // 교대로 잰다. 몰아서 재면 컨테이너나 디스크 상태의 표류가 한쪽에만 실린다.
                java.util.List<Double> bare = new java.util.ArrayList<>(), indexed = new java.util.ArrayList<>();
                for (int i = 0; i < ROUNDS; i++) {
                    bare.add(insert(ds, "orders_bare"));
                    indexed.add(insert(ds, "orders_indexed"));
                    System.out.printf("  %d회차  인덱스1개 %.2fs   인덱스3개 %.2fs%n",
                            i + 1, bare.get(i), indexed.get(i));
                }
                bare.sort(Double::compare); indexed.sort(Double::compare);
                double b = bare.get(ROUNDS / 2), x = indexed.get(ROUNDS / 2);
                double spreadB = (bare.get(ROUNDS - 1) - bare.get(0)) / b * 100;
                double spreadX = (indexed.get(ROUNDS - 1) - indexed.get(0)) / x * 100;

                System.out.printf("%n=== INSERT %,d행 (배치 %,d) · %d회 중앙값 ===%n", ROWS, BATCH, ROUNDS);
                System.out.printf("  인덱스 1개  %.2fs  (%,.0f행/s)  회차 간 폭 %.1f%%%n", b, ROWS / b, spreadB);
                System.out.printf("  인덱스 3개  %.2fs  (%,.0f행/s)  회차 간 폭 %.1f%%%n", x, ROWS / x, spreadX);
                double diff = (x / b - 1) * 100;
                double noise = Math.max(spreadB, spreadX);
                System.out.printf(">>> 차이 %+.1f%% · 회차 간 폭 %.1f%% → %s%n", diff, noise,
                        Math.abs(diff) > noise ? "노이즈보다 크다" : "노이즈와 구별되지 않는다");
                assertThat(x).isGreaterThan(0);
            }
        }
    }

    private double insert(HikariDataSource ds, String table) throws SQLException {
        long t0 = System.nanoTime();
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO " + table + " (user_id, order_no, status, total_amount, currency, version," +
                    " created_at, updated_at, expires_at) VALUES (?,?,'PAID',10000,'KRW',0,NOW(6),NOW(6),NOW(6))")) {
                for (int i = 1; i <= ROWS; i++) {
                    ps.setLong(1, i % 10_000);
                    ps.setString(2, table + "-" + System.nanoTime() + "-" + i);
                    ps.addBatch();
                    if (i % BATCH == 0) { ps.executeBatch(); c.commit(); }
                }
                ps.executeBatch(); c.commit();
            }
        }
        return (System.nanoTime() - t0) / 1_000_000_000.0;
    }
}
