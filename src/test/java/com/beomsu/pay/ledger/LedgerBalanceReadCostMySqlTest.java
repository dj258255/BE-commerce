package com.beomsu.pay.ledger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.MySQLContainer;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 잔액 조회(<b>SUM(signed amount)</b>)의 비용이 행 수에 따라 어떻게 늘고, 커버링 인덱스가
 * 얼마를 버는지 잰다(ADR-025).
 *
 * <p>잔액은 원장을 유일한 진실로 두고 매번 다시 센다 — 그래서 원장과 갈라질 수 없고, 대신 비용이
 * 행 수에 비례한다. 이 테스트가 그 비례의 기울기와, 인덱스가 어디까지를 버티게 하는지를 숫자로 남긴다.
 *
 * <p>{@code 1000만 행}은 CI 시간이 크므로 여기서는 {@code 10만 · 100만}을 잰다. 1000만 행은 같은
 * 하네스를 서버 안에서 돌리는 {@code tools/measure-ledger-balance.sh} 로 잰다(성능 리포트 16절).
 *
 * <p>같은 {@code ledger_entries} 를 인덱스 유무만 바꿔 두 번 잰다. 인덱스를 걸면 조회가 빨라지는
 * 대신 쓰기가 느려지므로, 그 대가는 {@link com.beomsu.pay.order.IndexWriteCostMySqlTest} 와 같은
 * 방식으로 따로 잰다.
 */
@Tag("integration")
class LedgerBalanceReadCostMySqlTest {

    private static final long[] SIZES = {100_000, 1_000_000};
    private static final int BATCH = 1_000;
    private static final int RUNS = 20;   // 1회만 재면 몇 ms 차이는 노이즈와 구별되지 않는다

    private static final String TX_DDL = """
            CREATE TABLE ledger_transactions (
              id BIGINT NOT NULL AUTO_INCREMENT,
              tx_type VARCHAR(40) NOT NULL,
              source_type VARCHAR(30) NOT NULL,
              source_id BIGINT NOT NULL,
              source_seq INT NOT NULL,
              description VARCHAR(200),
              created_at DATETIME(6) NOT NULL,
              PRIMARY KEY (id)
            ) ENGINE=InnoDB""";

    // V1 + V50. account enum 에 CASH · PG_FEE 가 있어야 입금 분개가 저장된다(잠복 버그의 회귀 가드).
    private static final String ENTRY_DDL = """
            CREATE TABLE ledger_entries (
              id BIGINT NOT NULL AUTO_INCREMENT,
              transaction_id BIGINT NOT NULL,
              account ENUM('PG_RECEIVABLE','SALES','CASH','PG_FEE') NOT NULL,
              direction ENUM('DEBIT','CREDIT') NOT NULL,
              amount BIGINT NOT NULL,
              PRIMARY KEY (id),
              KEY idx_ledger_entries_account_covering (account, direction, amount),
              CONSTRAINT fk_le_tx FOREIGN KEY (transaction_id) REFERENCES ledger_transactions(id)
            ) ENGINE=InnoDB""";

    private static final String SINGLE = """
            SELECT COALESCE(SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END), 0)
            FROM ledger_entries WHERE account='PG_RECEIVABLE'""";
    private static final String GROUP_BY = """
            SELECT SUM(bal) FROM (
              SELECT SUM(CASE WHEN direction='DEBIT' THEN amount ELSE -amount END) bal
              FROM ledger_entries GROUP BY account) t""";

    @Test
    @DisplayName("잔액 SUM 조회 비용: 행 수에 비례하고, 커버링 인덱스가 그 기울기를 낮춘다")
    void readCost() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("ledgerbench")
                .withCommand("--innodb-buffer-pool-size=536870912")) {
            mysql.start();
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(mysql.getJdbcUrl());
            cfg.setUsername(mysql.getUsername());
            cfg.setPassword(mysql.getPassword());
            cfg.setMaximumPoolSize(2);
            try (HikariDataSource ds = new HikariDataSource(cfg);
                 Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute(TX_DDL);
                s.execute(ENTRY_DDL);
                s.execute("INSERT INTO ledger_transactions(tx_type,source_type,source_id,source_seq,description,created_at)"
                        + " VALUES('BENCH','BENCH',0,0,'bench',NOW(6))");

                System.out.printf("%n=== 원장 잔액 조회 비용 (INSERT %,d행 · %d회) ===%n", SIZES[SIZES.length - 1], RUNS);
                System.out.printf("%-12s %-10s %10s %10s %10s%n", "행수", "인덱스", "p50(ms)", "p95(ms)", "max(ms)");

                long current = 0;
                List<Double> indexedSingle = List.of(), indexedGroup = List.of(), noIndexSingle = List.of();
                for (long size : SIZES) {
                    insertTo(ds, size - current);
                    current = size;
                    indexedSingle = measure(ds, SINGLE);
                    indexedGroup = measure(ds, GROUP_BY);
                    System.out.printf("%-12d %-10s %10.1f %10.1f %10.1f%n",
                            size, "있음(single)", p50(indexedSingle), p95(indexedSingle), max(indexedSingle));
                    System.out.printf("%-12d %-10s %10.1f %10.1f %10.1f%n",
                            size, "있음(group)", p50(indexedGroup), p95(indexedGroup), max(indexedGroup));
                }

                // 인덱스를 떼고 가장 큰 크기에서 다시 잰다 → 인덱스가 버는 몫
                s.execute("DROP INDEX idx_ledger_entries_account_covering ON ledger_entries");
                noIndexSingle = measure(ds, SINGLE);
                System.out.printf("%-12d %-10s %10.1f %10.1f %10.1f%n",
                        SIZES[SIZES.length - 1], "없음(single)", p50(noIndexSingle), p95(noIndexSingle), max(noIndexSingle));
                s.execute("CREATE INDEX idx_ledger_entries_account_covering ON ledger_entries(account, direction, amount)");

                double gain = p95(noIndexSingle) / p95(indexedSingle);
                System.out.printf(">>> %,d 행에서 single 계정 잔액 p95: 인덱스 없음 %.1fms → 있음 %.1fms (%.1f배)%n",
                        SIZES[SIZES.length - 1], p95(noIndexSingle), p95(indexedSingle), gain);
            }
        }
    }

    @Test
    @DisplayName("account enum 이 CASH·PG_FEE 를 받는다 — 입금 분개가 저장되지 않던 잠복 버그의 회귀 가드")
    void payoutAccountsAreWritable() throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("ledgerenum")) {
            mysql.start();
            HikariConfig cfg = new HikariConfig();
            cfg.setJdbcUrl(mysql.getJdbcUrl());
            cfg.setUsername(mysql.getUsername());
            cfg.setPassword(mysql.getPassword());
            try (HikariDataSource ds = new HikariDataSource(cfg);
                 Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute(TX_DDL);
                s.execute(ENTRY_DDL);
                s.execute("INSERT INTO ledger_transactions(id,tx_type,source_type,source_id,source_seq,description,created_at)"
                        + " VALUES(1,'SETTLEMENT_PAID_OUT','SETTLEMENT',7,0,'지급',NOW(6))");
                // V1 의 enum(PG_RECEIVABLE·SALES)에는 이 두 값이 없어 입금 분개가 DB에서 거부됐다.
                s.execute("INSERT INTO ledger_entries(transaction_id,account,direction,amount) VALUES"
                        + " (1,'CASH','DEBIT',97030),(1,'PG_FEE','DEBIT',2970),(1,'PG_RECEIVABLE','CREDIT',100000)");
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM ledger_entries WHERE account IN ('CASH','PG_FEE')")) {
                    rs.next();
                    assertThat(rs.getInt(1)).isEqualTo(2);
                }
            }
        }
    }

    private void insertTo(HikariDataSource ds, long rows) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO ledger_entries(transaction_id,account,direction,amount) VALUES(1,?,?,?)")) {
                for (long i = 0; i < rows; i++) {
                    // 4계정을 고르게 섞고, 차변/대변을 번갈아 둔다.
                    ps.setString(1, switch ((int) (i % 4)) {
                        case 0 -> "PG_RECEIVABLE";
                        case 1 -> "SALES";
                        case 2 -> "CASH";
                        default -> "PG_FEE";
                    });
                    ps.setString(2, i % 2 == 0 ? "DEBIT" : "CREDIT");
                    ps.setLong(3, 10_000);
                    ps.addBatch();
                    if (i % BATCH == 0) {
                        ps.executeBatch();
                        c.commit();
                    }
                }
                ps.executeBatch();
                c.commit();
            }
        }
    }

    private List<Double> measure(HikariDataSource ds, String query) throws SQLException {
        List<Double> ms = new ArrayList<>(RUNS);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            for (int i = 0; i < RUNS + 2; i++) {   // 앞 2회는 워밍업으로 버린다
                long t0 = System.nanoTime();
                try (ResultSet rs = s.executeQuery(query)) {
                    rs.next();
                }
                if (i >= 2) {
                    ms.add((System.nanoTime() - t0) / 1_000_000.0);
                }
            }
        }
        ms.sort(Double::compare);
        return ms;
    }

    private double p50(List<Double> sorted) {
        return sorted.get(sorted.size() / 2);
    }

    private double p95(List<Double> sorted) {
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(0.95 * sorted.size()) - 1));
    }

    private double max(List<Double> sorted) {
        return sorted.get(sorted.size() - 1);
    }
}
