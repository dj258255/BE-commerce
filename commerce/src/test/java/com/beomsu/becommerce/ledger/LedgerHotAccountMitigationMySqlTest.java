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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 핫 계정 해법 비교(#494). {@link LedgerHotEntityContentionMySqlTest} 와 같은 하네스(워커 8, 워커당 200회,
 * 트랜잭션 안 임계 구간 2ms)에서 수취 전용 계정의 잔액 갱신을 다섯 모양으로 잰다.
 *
 * <ul>
 *   <li>COLD: 워커마다 다른 행(상한 기준선)</li>
 *   <li>HOT: 모든 워커가 한 행</li>
 *   <li>SPLIT-k: 하위 행 k개 중 무작위 하나. 잔액은 하위 행의 합</li>
 *   <li>BUFFER: 분개 행만 INSERT 하고 집계기가 100ms마다 모아 잔액 행에 한 번 반영. 잔액은 늦게 맞는다</li>
 * </ul>
 *
 * <p>같은 맥의 다른 작업 부하에 흔들리므로 절대값 대신 같은 회차 COLD 대비 비율로 판정한다.
 * 회차마다 표를 새로 만들어 앞 회차의 행 수가 다음 회차에 섞이지 않게 한다.
 */
@Tag("measurement")
class LedgerHotAccountMitigationMySqlTest {

    private static final int AGGREGATE_INTERVAL_MS = 100;

    @Test
    @DisplayName("핫 계정을 하위 행으로 나누거나 버퍼로 모으면 처리량이 얼마나 돌아오나")
    void compareMitigations() throws Exception {
        int workers = Integer.getInteger("ledger.hot.workers", 8);
        int ops = Integer.getInteger("ledger.hot.operationsPerWorker", 200);
        int csMs = Integer.getInteger("ledger.hot.criticalSectionMs", 2);
        int rounds = Integer.getInteger("ledger.hot.rounds", 5);

        try (MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("hotbench")
                .withUsername("bench")
                .withPassword("bench")) {
            mysql.start();

            Map<String, List<Result>> results = new LinkedHashMap<>();
            String shapes = System.getProperty("ledger.hot.shapes", "COLD,HOT,SPLIT-4,SPLIT-16,BUFFER");
            for (String shape : shapes.split(",")) {
                results.put(shape, new ArrayList<>());
            }

            for (int round = 1; round <= rounds; round++) {
                for (String shape : results.keySet()) {
                    resetSchema(mysql, workers, !shape.equals("BUFFER-NOIDX"));
                    Result r = shape.startsWith("BUFFER")
                            ? measureBuffer(mysql, workers, ops, csMs)
                            : measureUpdate(mysql, shape, workers, ops, csMs);
                    results.get(shape).add(r);
                    System.out.printf("round=%d %-9s ops=%d elapsed_ms=%d tput=%.1f p50=%.0f p95=%.0f p99=%.0f "
                                    + "lock_waits=%d final_balance=%d lag_p95_ms=%.0f lag_max_ms=%.0f%n",
                            round, shape, r.operations, r.elapsedMs, r.throughput, r.p50, r.p95, r.p99,
                            r.rowLockWaits, r.finalBalance, r.lagP95Ms, r.lagMaxMs);
                }
            }

            // 판정은 같은 회차 COLD 대비 비율의 중앙값으로 한다. 모양마다 따로 고른 회차를 섞지 않는다(#494 1차 결함).
            System.out.printf("%n=== hot account mitigations: median of per-round ratios vs COLD (%d rounds) ===%n", rounds);
            System.out.println("shape      tput_vs_cold  p95_vs_cold  lock_waits(med)  lag_p95_ms(med)  lag_max_ms(max)  balance_ok");
            List<Result> colds = results.get("COLD");
            for (Map.Entry<String, List<Result>> e : results.entrySet()) {
                List<Double> tput = new ArrayList<>();
                List<Double> p95 = new ArrayList<>();
                List<Double> waits = new ArrayList<>();
                List<Double> lagP95 = new ArrayList<>();
                double lagMax = 0;
                for (int i = 0; i < e.getValue().size(); i++) {
                    Result r = e.getValue().get(i);
                    Result c = colds.get(i);
                    tput.add(100.0 * r.throughput / c.throughput);
                    p95.add(r.p95 / Math.max(1, c.p95));
                    waits.add((double) r.rowLockWaits);
                    lagP95.add(r.lagP95Ms);
                    lagMax = Math.max(lagMax, r.lagMaxMs);
                }
                boolean balanceOk = e.getValue().stream().allMatch(r -> r.finalBalance == r.operations);
                System.out.printf("%-9s %11.1f%% %11.2fx %16.0f %16.0f %16.0f  %s%n",
                        e.getKey(), med(tput), med(p95), med(waits), med(lagP95), lagMax, balanceOk);
            }

            for (List<Result> list : results.values()) {
                for (Result r : list) {
                    assertThat(r.operations).isEqualTo(workers * ops);
                    assertThat(r.finalBalance).as("최종 잔액 == 처리 건수").isEqualTo(r.operations);
                }
            }
        }
    }

    // ---- UPDATE 모양(COLD · HOT · SPLIT-k) ----

    private static Result measureUpdate(MySQLContainer<?> mysql, String shape, int workers, int ops, int csMs)
            throws Exception {
        int splits = shape.startsWith("SPLIT-") ? Integer.parseInt(shape.substring(6)) : 0;
        long[] lockBefore = lockStatus(mysql);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        long elapsedMs = runWorkers(workers, (workerId, ready, start) -> {
            try (Connection c = connection(mysql);
                 PreparedStatement update = c.prepareStatement(
                         "UPDATE hot_entity SET balance = balance + 1 WHERE account_id = ?");
                 Statement sleep = c.createStatement()) {
                c.setAutoCommit(false);
                ready.countDown();
                start.await();
                for (int i = 0; i < ops; i++) {
                    String account = switch (shape) {
                        case "COLD" -> "COLD-" + workerId;
                        case "HOT" -> "HOT";
                        default -> "HOT-" + ThreadLocalRandom.current().nextInt(splits);
                    };
                    long t0 = System.nanoTime();
                    update.setString(1, account);
                    update.executeUpdate();
                    sleep.execute("SELECT SLEEP(" + (csMs / 1000.0) + ")");
                    c.commit();
                    latencies.add((System.nanoTime() - t0) / 1_000_000L);
                }
            }
        });
        long[] lockAfter = lockStatus(mysql);
        String balanceSql = switch (shape) {
            case "COLD" -> "SELECT SUM(balance) FROM hot_entity WHERE account_id LIKE 'COLD-%'";
            case "HOT" -> "SELECT balance FROM hot_entity WHERE account_id = 'HOT'";
            default -> "SELECT SUM(balance) FROM hot_entity WHERE account_id LIKE 'HOT-%'";
        };
        return result(latencies, elapsedMs, lockAfter[0] - lockBefore[0], scalar(mysql, balanceSql), List.of());
    }

    // ---- BUFFER 모양 ----

    private static Result measureBuffer(MySQLContainer<?> mysql, int workers, int ops, int csMs) throws Exception {
        long[] lockBefore = lockStatus(mysql);
        ConcurrentLinkedQueue<Long> latencies = new ConcurrentLinkedQueue<>();
        List<Double> lags = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean writersDone = new AtomicBoolean(false);

        Thread aggregator = new Thread(() -> {
            try (Connection c = connection(mysql)) {
                c.setAutoCommit(false);
                while (true) {
                    int applied = aggregateOnce(c, lags);
                    if (writersDone.get() && applied == 0 && pending(c) == 0) {
                        return;
                    }
                    Thread.sleep(AGGREGATE_INTERVAL_MS);
                }
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "buffer-aggregator");
        aggregator.start();

        long elapsedMs;
        try {
            elapsedMs = runWorkers(workers, (workerId, ready, start) -> {
                try (Connection c = connection(mysql);
                     PreparedStatement insert = c.prepareStatement(
                             "INSERT INTO hot_journal(account_id, amount, created_ns) VALUES ('HOT', 1, ?)");
                     Statement sleep = c.createStatement()) {
                    c.setAutoCommit(false);
                    ready.countDown();
                    start.await();
                    for (int i = 0; i < ops; i++) {
                        long t0 = System.nanoTime();
                        insert.setLong(1, System.nanoTime());
                        insert.executeUpdate();
                        sleep.execute("SELECT SLEEP(" + (csMs / 1000.0) + ")");
                        c.commit();
                        latencies.add((System.nanoTime() - t0) / 1_000_000L);
                    }
                }
            });
        } finally {
            writersDone.set(true);
            aggregator.join(60_000);
        }
        assertThat(aggregator.isAlive()).as("집계기가 남은 분개를 다 반영하고 끝남").isFalse();
        long[] lockAfter = lockStatus(mysql);
        long balance = scalar(mysql, "SELECT balance FROM hot_entity WHERE account_id = 'HOT'");
        return result(latencies, elapsedMs, lockAfter[0] - lockBefore[0], balance, new ArrayList<>(lags));
    }

    /**
     * 아직 반영하지 않은 분개를 비잠금 읽기로 고르고, 그 행만 PK 로 표시한 뒤 표시된 수만큼 잔액에 더한다.
     * 범위를 잠그는 SELECT … FOR UPDATE 는 새 INSERT 를 갭 락으로 막아 버퍼의 이점을 지우므로 쓰지 않는다.
     */
    private static int aggregateOnce(Connection c, List<Double> lags) throws SQLException {
        List<Long> ids = new ArrayList<>();
        List<Long> created = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, created_ns FROM hot_journal WHERE applied = 0 ORDER BY id LIMIT 5000");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                ids.add(rs.getLong(1));
                created.add(rs.getLong(2));
            }
        }
        if (ids.isEmpty()) {
            c.commit();
            return 0;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            in.append(i == 0 ? "" : ",").append(ids.get(i));
        }
        int marked;
        try (Statement s = c.createStatement()) {
            // 읽은 행만 PK 로 잠근다. 표가 작을 때 옵티마이저가 전체 훑기를 고르면 REPEATABLE READ 의 잠금 읽기가
            // 아직 커밋 안 된 새 분개마다 기다리며 표 끝을 쫓아가 쓰기가 멈출 때까지 한 번도 반영하지 못한다(#494 2차 추적).
            // 집계기는 하나라 같은 행을 두 번 표시하지 않는다.
            marked = s.executeUpdate("UPDATE hot_journal FORCE INDEX (PRIMARY) SET applied = 1 WHERE id IN (" + in + ")");
            s.executeUpdate("UPDATE hot_entity SET balance = balance + " + marked + " WHERE account_id = 'HOT'");
        }
        c.commit();
        long now = System.nanoTime();
        if (Boolean.getBoolean("ledger.hot.trace")) {
            System.out.printf("trace pass marked=%d selected=%d oldest_lag_ms=%.0f%n",
                    marked, ids.size(), (now - created.get(0)) / 1_000_000.0);
        }
        for (Long ns : created) {
            lags.add((now - ns) / 1_000_000.0);
        }
        return marked;
    }

    private static long pending(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM hot_journal WHERE applied = 0")) {
            rs.next();
            long n = rs.getLong(1);
            c.commit();
            return n;
        }
    }

    // ---- 공통 ----

    @FunctionalInterface
    private interface WorkerBody {
        void run(int workerId, CountDownLatch ready, CountDownLatch start) throws Exception;
    }

    private static long runWorkers(int workers, WorkerBody body) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch ready = new CountDownLatch(workers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int w = 0; w < workers; w++) {
                int id = w;
                futures.add(pool.submit(() -> {
                    body.run(id, ready, start);
                    return null;
                }));
            }
            ready.await();
            long t0 = System.nanoTime();
            start.countDown();
            for (Future<?> f : futures) {
                f.get();
            }
            return (System.nanoTime() - t0) / 1_000_000L;
        } finally {
            pool.shutdownNow();
        }
    }

    private static void resetSchema(MySQLContainer<?> mysql, int workers, boolean appliedIndex) throws SQLException {
        try (Connection c = connection(mysql); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS hot_entity");
            s.execute("DROP TABLE IF EXISTS hot_journal");
            s.execute("CREATE TABLE hot_entity (account_id VARCHAR(64) PRIMARY KEY, balance BIGINT NOT NULL) ENGINE=InnoDB");
            s.execute("CREATE TABLE hot_journal (id BIGINT AUTO_INCREMENT PRIMARY KEY, account_id VARCHAR(64) NOT NULL, "
                    + "amount BIGINT NOT NULL, created_ns BIGINT NOT NULL, applied TINYINT NOT NULL DEFAULT 0"
                    + (appliedIndex ? ", KEY idx_applied (applied, id)" : "") + ") ENGINE=InnoDB");
            s.execute("INSERT INTO hot_entity VALUES ('HOT', 0)");
            for (int i = 0; i < Math.max(workers, 16); i++) {
                s.execute("INSERT INTO hot_entity VALUES ('COLD-" + i + "', 0)");
                s.execute("INSERT INTO hot_entity VALUES ('HOT-" + i + "', 0)");
            }
        }
    }

    private static Result result(ConcurrentLinkedQueue<Long> latencies, long elapsedMs, long lockWaits,
                                 long finalBalance, List<Double> lags) {
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        List<Double> lagSorted = new ArrayList<>(lags);
        Collections.sort(lagSorted);
        return new Result(sorted.size(), elapsedMs, sorted.size() * 1000.0 / Math.max(1, elapsedMs),
                pct(sorted, 0.50), pct(sorted, 0.95), pct(sorted, 0.99), lockWaits, finalBalance,
                lagSorted.isEmpty() ? 0 : lagSorted.get(Math.max(0, (int) Math.ceil(lagSorted.size() * 0.95) - 1)),
                lagSorted.isEmpty() ? 0 : lagSorted.get(lagSorted.size() - 1));
    }

    private static double med(List<Double> xs) {
        List<Double> sorted = new ArrayList<>(xs);
        Collections.sort(sorted);
        return sorted.get(sorted.size() / 2);
    }

    private static double pct(List<Long> sorted, double p) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * p) - 1));
    }

    private static long[] lockStatus(MySQLContainer<?> mysql) throws SQLException {
        try (Connection c = connection(mysql);
             PreparedStatement ps = c.prepareStatement("SHOW GLOBAL STATUS LIKE 'Innodb_row_lock_waits'");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return new long[]{rs.getLong(2)};
        }
    }

    private static long scalar(MySQLContainer<?> mysql, String sql) throws SQLException {
        try (Connection c = connection(mysql); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection connection(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
    }

    private record Result(int operations, long elapsedMs, double throughput, double p50, double p95, double p99,
                          long rowLockWaits, long finalBalance, double lagP95Ms, double lagMaxMs) {
    }
}
