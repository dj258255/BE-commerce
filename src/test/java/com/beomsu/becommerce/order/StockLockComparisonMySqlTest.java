package com.beomsu.becommerce.order;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 차감 락 3종 비교 — <b>실제 MySQL(InnoDB)</b>에서 잰다.
 *
 * <p><b>왜 {@link StockLockComparisonTest}(H2)와 따로 두는가</b><br>
 * H2 테스트는 CI에서 매번 같은 결과가 나오는 <b>안전 불변식 검사</b>다(초과판매 0, 재고 음수 없음,
 * 일관성). 목적이 다르므로 그대로 둔다. 다만 H2는 {@code MODE=MySQL}이어도 저장 엔진이 InnoDB가
 * 아니다. 갭 락·넥스트키 락이 없고, 데드락 탐지 대신 {@code LOCK_TIMEOUT}으로 풀며, 인메모리라
 * 버퍼 풀·redo·fsync가 통째로 빠진다. 그래서 <b>소요 시간은 MySQL 수치로 쓸 수 없다.</b>
 * 이 테스트가 그 숫자를 실제 InnoDB에서 다시 잰다.
 *
 * <p><b>측정 설계</b><br>
 * 커넥션 획득 비용이 락 동작을 가리지 않도록 세 전략 모두 같은 Hikari 풀(크기 {@value #POOL})을
 * 공유한다. 스레드 수({@value #THREADS})보다 크게 잡아 풀 대기가 0이 되게 했다. JIT·컨테이너
 * 워밍업 후 {@value #ROUNDS}회 측정해 중앙값을 쓴다. 전략마다 재고를 초기화하고 같은 순간에
 * 출발시킨다(CountDownLatch).
 *
 * <p>기본 스위트에서는 제외한다(Docker 필요). {@code ./gradlew integrationTest}로 실행.
 */
@Tag("integration")
class StockLockComparisonMySqlTest {

    private static final int STOCK = 20;
    private static final int THREADS = 30;
    private static final int HIGH_THREADS = 150;
    private static final int POOL = 170;
    private static final int ROUNDS = 5;
    private static final int WARMUP = 2;
    private static final int OPTIMISTIC_MAX_RETRY = 50;

    private static MySQLContainer<?> mysql;
    private static HikariDataSource ds;

    /** InnoDB 고유 신호 — 데드락(1213)과 락 대기 타임아웃(1205)은 H2에서 관측 자체가 불가능하다. */
    private static final AtomicInteger deadlocks = new AtomicInteger();
    private static final AtomicInteger lockTimeouts = new AtomicInteger();

    @BeforeAll
    static void startDb() throws SQLException {
        mysql = new MySQLContainer<>("mysql:8.4")
                .withDatabaseName("locktest")
                .withCommand("--innodb-flush-log-at-trx-commit=1", "--max-connections=400");
        mysql.start();

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(mysql.getJdbcUrl());
        cfg.setUsername(mysql.getUsername());
        cfg.setPassword(mysql.getPassword());
        cfg.setMaximumPoolSize(POOL);
        cfg.setMinimumIdle(POOL);
        ds = new HikariDataSource(cfg);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE stock (id BIGINT PRIMARY KEY, quantity INT NOT NULL, version BIGINT NOT NULL) ENGINE=InnoDB");
            try (ResultSet rs = s.executeQuery("SELECT VERSION()")) {
                rs.next();
                System.out.printf("%n=== 실 MySQL %s · InnoDB · 재고 %d · 스레드 %d · 풀 %d ===%n",
                        rs.getString(1), STOCK, THREADS, POOL);
            }
        }
    }

    @AfterAll
    static void stopDb() {
        if (ds != null) ds.close();
        if (mysql != null) mysql.stop();
    }

    @Test
    @DisplayName("조건부 UPDATE: 초과판매 0 + 정확히 완판")
    void conditional() throws Exception {
        measure("조건부 UPDATE", this::deductConditional, THREADS, true);
    }

    @Test
    @DisplayName("비관적 락(FOR UPDATE): 초과판매 0 + 정확히 완판")
    void pessimistic() throws Exception {
        measure("비관적 락", this::deductPessimistic, THREADS, true);
    }

    @Test
    @DisplayName("낙관적 락(version+재시도): 초과판매 0 (고경합 시 미달판매 가능)")
    void optimistic() throws Exception {
        measure("낙관적 락", this::deductOptimistic, THREADS, false);
    }

    @Test
    @DisplayName("고경합(150스레드) 낙관적 락: 재시도 소진으로 미달판매가 나는가")
    void optimisticHighContention() throws Exception {
        measure("낙관적 락(고경합)", this::deductOptimistic, HIGH_THREADS, false);
    }

    @Test
    @DisplayName("고경합(150스레드) 조건부 UPDATE: 그래도 정확히 완판")
    void conditionalHighContention() throws Exception {
        measure("조건부 UPDATE(고경합)", this::deductConditional, HIGH_THREADS, true);
    }

    // --- 전략 3종 (H2 테스트와 동일한 SQL) ---

    /** 조건부 UPDATE — 락 없이 원자적. quantity >= 1일 때만 차감. */
    private boolean deductConditional() throws SQLException {
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE stock SET quantity = quantity - 1 WHERE id = 1 AND quantity >= 1")) {
            return ps.executeUpdate() == 1;
        }
    }

    /** 비관적 락 — SELECT ... FOR UPDATE로 행을 잠그고 차감. */
    private boolean deductPessimistic() throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                int qty;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT quantity FROM stock WHERE id = 1 FOR UPDATE");
                     ResultSet rs = sel.executeQuery()) {
                    rs.next();
                    qty = rs.getInt(1);
                }
                if (qty < 1) {
                    c.rollback();
                    return false;
                }
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE stock SET quantity = quantity - 1 WHERE id = 1")) {
                    up.executeUpdate();
                }
                c.commit();
                return true;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    /** 낙관적 락 — version 비교 후 충돌 시 재시도. 소진하면 미달판매. */
    private boolean deductOptimistic() throws SQLException {
        for (int attempt = 0; attempt < OPTIMISTIC_MAX_RETRY; attempt++) {
            try (Connection c = ds.getConnection()) {
                int qty;
                long version;
                try (PreparedStatement sel = c.prepareStatement(
                        "SELECT quantity, version FROM stock WHERE id = 1");
                     ResultSet rs = sel.executeQuery()) {
                    rs.next();
                    qty = rs.getInt(1);
                    version = rs.getLong(2);
                }
                if (qty < 1) {
                    return false;
                }
                try (PreparedStatement up = c.prepareStatement(
                        "UPDATE stock SET quantity = quantity - 1, version = version + 1 "
                                + "WHERE id = 1 AND version = ?")) {
                    up.setLong(1, version);
                    if (up.executeUpdate() == 1) {
                        return true;           // 버전 일치 → 성공
                    }
                    // 버전 충돌 → 재시도
                }
            }
        }
        return false;                          // 재시도 소진 → 미달판매
    }

    @FunctionalInterface
    private interface Deduct {
        boolean deductOne() throws SQLException;
    }

    /** 워밍업 후 ROUNDS회 재고, 중앙값을 채택한다. 불변식은 매 회차 검증한다. */
    private void measure(String name, Deduct deduct, int threads, boolean expectFullSellOut) throws Exception {
        for (int i = 0; i < WARMUP; i++) {
            runOnce(deduct, threads, expectFullSellOut);
        }
        long[] took = new long[ROUNDS];
        for (int i = 0; i < ROUNDS; i++) {
            took[i] = runOnce(deduct, threads, expectFullSellOut);
        }
        java.util.Arrays.sort(took);
        System.out.printf("[%s] 스레드=%d 중앙값=%dms (최소=%d 최대=%d, %d회) 마지막회차 성공=%d/%d 데드락=%d 락대기타임아웃=%d%n",
                name, threads, took[ROUNDS / 2], took[0], took[ROUNDS - 1], ROUNDS,
                lastSuccess, STOCK, deadlocks.get(), lockTimeouts.get());
    }

    private int lastSuccess;

    private long runOnce(Deduct deduct, int threads, boolean expectFullSellOut) throws Exception {
        reset();
        ExecutorService pool = Executors.newFixedThreadPool(threads + 2);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (deduct.deductOne()) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1213) deadlocks.incrementAndGet();
                    if (e.getErrorCode() == 1205) lockTimeouts.incrementAndGet();
                    failed.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            });
        }
        ready.await();
        long began = System.nanoTime();
        start.countDown();
        pool.shutdown();
        boolean done = pool.awaitTermination(60, TimeUnit.SECONDS);
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        int finalQty = currentQuantity();

        assertThat(done).isTrue();
        assertThat(finalQty).isGreaterThanOrEqualTo(0);                 // 재고 음수 없음
        assertThat(success.get()).isLessThanOrEqualTo(STOCK);           // 초과판매 없음
        assertThat(success.get() + failed.get()).isEqualTo(threads);    // 전부 집계
        assertThat(finalQty).isEqualTo(STOCK - success.get());          // 일관성

        if (expectFullSellOut) {
            assertThat(success.get()).isEqualTo(STOCK);                 // 완판
            assertThat(finalQty).isZero();
        }
        lastSuccess = success.get();
        return tookMs;
    }

    private void reset() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM stock");
            s.execute("INSERT INTO stock VALUES (1, " + STOCK + ", 0)");
        }
    }

    private int currentQuantity() throws SQLException {
        try (Connection c = ds.getConnection();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT quantity FROM stock WHERE id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
