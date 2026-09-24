package com.beomsu.becommerce.ledger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 원장 잔액을 같은 행에 미리 저장한다고 가정했을 때 hot entity가 만드는 직렬화 비용을 측정한다.
 * 실제 ledger_entries는 append-only라 애플리케이션 DB가 아니라 별도 MySQL 컨테이너에서 실행한다.
 */
@Tag("measurement")   // 수치를 재는 측정이다 — PR 마다가 아니라 매일 돈다(#274)
class LedgerHotEntityContentionMySqlTest {

    @Test
    @DisplayName("같은 잔액 행을 갱신하면 lock wait와 p95가 증가한다")
    void hotEntitySerializesUpdates() throws Exception {
        int workers = Integer.getInteger("ledger.hot.workers", 8);
        int operationsPerWorker = Integer.getInteger("ledger.hot.operationsPerWorker", 200);
        int criticalSectionMs = Integer.getInteger("ledger.hot.criticalSectionMs", 2);

        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("hotbench")
                .withUsername("bench")
                .withPassword("bench")) {
            mysql.start();
            try (Connection setup = connection(mysql)) {
                setupSchema(setup, workers);
            }

            Measurement hot = measure(mysql, workers, operationsPerWorker, criticalSectionMs, true);
            Measurement cold = measure(mysql, workers, operationsPerWorker, criticalSectionMs, false);

            System.out.printf(
                    "\n=== ledger hot entity contention (MySQL 8.4 InnoDB) ===%n" +
                            "workers=%d ops_per_worker=%d critical_section_ms=%d%n" +
                            "case                 ops  elapsed_ms  throughput/s  p50_ms  p95_ms  p99_ms  row_lock_waits  row_lock_time_ms%n" +
                            "hot(same account)    %4d %10d %12.1f %7.2f %7.2f %7.2f %15d %17d%n" +
                            "cold(separate rows) %4d %10d %12.1f %7.2f %7.2f %7.2f %15d %17d%n",
                    workers, operationsPerWorker, criticalSectionMs,
                    hot.operations, hot.elapsedMs, hot.throughputPerSecond, hot.p50Ms, hot.p95Ms, hot.p99Ms,
                    hot.rowLockWaits, hot.rowLockTimeMs,
                    cold.operations, cold.elapsedMs, cold.throughputPerSecond, cold.p50Ms, cold.p95Ms, cold.p99Ms,
                    cold.rowLockWaits, cold.rowLockTimeMs
            );

            assertThat(hot.operations).isEqualTo(workers * operationsPerWorker);
            assertThat(cold.operations).isEqualTo(workers * operationsPerWorker);
            assertThat(hot.p95Ms).isGreaterThan(cold.p95Ms);
            assertThat(hot.throughputPerSecond).isLessThan(cold.throughputPerSecond);
        }
    }

    private static Measurement measure(MySQLContainer<?> mysql,
                                       int workers,
                                       int operationsPerWorker,
                                       int criticalSectionMs,
                                       boolean hot) throws Exception {
        long waitsBefore;
        long lockTimeBefore;
        try (Connection status = connection(mysql)) {
            waitsBefore = status(status, "Innodb_row_lock_waits");
            lockTimeBefore = status(status, "Innodb_row_lock_time");
        }

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long started = System.nanoTime();
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int worker = 0; worker < workers; worker++) {
                int workerId = worker;
                futures.add(pool.submit(() -> {
                    try (Connection c = connection(mysql);
                         PreparedStatement update = c.prepareStatement(
                                 "UPDATE hot_entity SET balance = balance + 1 WHERE account_id = ?");
                         Statement sleep = c.createStatement()) {
                        c.setAutoCommit(false);
                        ready.countDown();
                        start.await();
                        for (int i = 0; i < operationsPerWorker; i++) {
                            String account = hot ? "HOT" : "COLD-" + workerId;
                            long operationStarted = System.nanoTime();
                            update.setString(1, account);
                            update.executeUpdate();
                            sleep.execute("SELECT SLEEP(" + (criticalSectionMs / 1000.0) + ")");
                            c.commit();
                            latencies.add((System.nanoTime() - operationStarted) / 1_000_000L);
                        }
                    }
                    return null;
                }));
            }
            ready.await();
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
        long waitsAfter;
        long lockTimeAfter;
        try (Connection status = connection(mysql)) {
            waitsAfter = status(status, "Innodb_row_lock_waits");
            lockTimeAfter = status(status, "Innodb_row_lock_time");
        }

        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        return new Measurement(
                sorted.size(),
                elapsedMs,
                sorted.isEmpty() ? 0 : sorted.size() * 1000.0 / Math.max(1, elapsedMs),
                percentile(sorted, 0.50),
                percentile(sorted, 0.95),
                percentile(sorted, 0.99),
                waitsAfter - waitsBefore,
                lockTimeAfter - lockTimeBefore
        );
    }

    private static void setupSchema(Connection c, int workers) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute("CREATE TABLE hot_entity (account_id VARCHAR(64) PRIMARY KEY, balance BIGINT NOT NULL) ENGINE=InnoDB");
            s.execute("INSERT INTO hot_entity(account_id, balance) VALUES ('HOT', 0)");
            for (int i = 0; i < workers; i++) {
                s.execute("INSERT INTO hot_entity(account_id, balance) VALUES ('COLD-" + i + "', 0)");
            }
        }
    }

    private static Connection connection(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private static long status(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SHOW GLOBAL STATUS LIKE ?")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                assertThat(rs.next()).isTrue();
                return rs.getLong(2);
            }
        }
    }

    private static double percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        int index = Math.max(0, (int) Math.ceil(sorted.size() * p) - 1);
        return sorted.get(index);
    }

    private record Measurement(int operations,
                               long elapsedMs,
                               double throughputPerSecond,
                               double p50Ms,
                               double p95Ms,
                               double p99Ms,
                               long rowLockWaits,
                               long rowLockTimeMs) {
    }
}
