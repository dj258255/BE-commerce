package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.catalog.StockDeductionService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재고 차감 락 3종 동시성 비교 — 실제 스레드로 두들겨 정합성과 성능을 실측한다.
 *
 * <p>{@link StockDeductionService}의 세 전략(조건부 UPDATE / 비관적 락 / 낙관적 락)에 대응하는 SQL을
 * H2 인메모리 DB에서 {@value #THREADS}개 스레드로 동시에 실행한다. Docker·Spring 부팅 없이 수초 안에
 * 끝나므로 CI에서 결정적으로 돌릴 수 있다.
 *
 * <p>모든 전략이 지켜야 할 <b>안전 불변식</b>: 초과판매 없음(성공 ≤ 재고), 재고 음수 없음,
 * 일관성(최종재고 = 재고 − 성공). 락 방식(조건부·비관적)은 정확히 완판까지 보장하고,
 * 낙관적 락은 고경합에서 재시도 소진으로 <b>미달판매</b>가 날 수 있다(이 차이가 실측으로 드러난다).
 */
class StockLockComparisonTest {

    private static final String URL =
            "jdbc:h2:mem:stocklock;DB_CLOSE_DELAY=-1;MODE=MySQL;LOCK_TIMEOUT=10000";
    private static final int STOCK = 20;
    private static final int THREADS = 30;
    private static final int OPTIMISTIC_MAX_RETRY = 50;

    @BeforeAll
    static void createSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE stock (id BIGINT PRIMARY KEY, quantity INT NOT NULL, version BIGINT NOT NULL)");
        }
    }

    @AfterAll
    static void dropSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DROP TABLE stock");
        }
    }

    @Test
    @DisplayName("조건부 UPDATE: 초과판매 0 + 정확히 완판")
    void conditional() throws Exception {
        runStrategy("조건부 UPDATE", this::deductConditional, true);
    }

    @Test
    @DisplayName("비관적 락(FOR UPDATE): 초과판매 0 + 정확히 완판")
    void pessimistic() throws Exception {
        runStrategy("비관적 락", this::deductPessimistic, true);
    }

    @Test
    @DisplayName("낙관적 락(version+재시도): 초과판매 0 (고경합 시 미달판매 가능)")
    void optimistic() throws Exception {
        runStrategy("낙관적 락", this::deductOptimistic, false);
    }

    // --- 전략 3종 (StockDeductionService의 JPA 구현에 대응하는 SQL) ---

    /** 조건부 UPDATE — 락 없이 원자적. quantity >= 1일 때만 차감. */
    private boolean deductConditional() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL);
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE stock SET quantity = quantity - 1 WHERE id = 1 AND quantity >= 1")) {
            return ps.executeUpdate() == 1;
        }
    }

    /** 비관적 락 — SELECT ... FOR UPDATE로 행을 잠그고 차감. */
    private boolean deductPessimistic() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL)) {
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
            }
        }
    }

    /** 낙관적 락 — version 비교 후 충돌 시 재시도. 소진하면 미달판매. */
    private boolean deductOptimistic() throws SQLException {
        for (int attempt = 0; attempt < OPTIMISTIC_MAX_RETRY; attempt++) {
            try (Connection c = DriverManager.getConnection(URL)) {
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

    private void runStrategy(String name, Deduct deduct, boolean expectFullSellOut) throws Exception {
        reset();
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        long began = System.nanoTime();
        for (int i = 0; i < THREADS; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    if (deduct.deductOne()) {
                        success.incrementAndGet();
                    } else {
                        failed.incrementAndGet();
                    }
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            });
        }
        ready.await();
        start.countDown();
        pool.shutdown();
        boolean done = pool.awaitTermination(20, TimeUnit.SECONDS);
        long tookMs = (System.nanoTime() - began) / 1_000_000;

        int finalQty = currentQuantity();
        System.out.printf("[%s] 성공=%d 실패=%d 최종재고=%d 소요=%dms%n",
                name, success.get(), failed.get(), finalQty, tookMs);

        assertThat(done).isTrue();
        assertThat(finalQty).isGreaterThanOrEqualTo(0);                 // 재고 음수 없음
        assertThat(success.get()).isLessThanOrEqualTo(STOCK);          // 초과판매 없음
        assertThat(success.get() + failed.get()).isEqualTo(THREADS);   // 전부 집계
        assertThat(finalQty).isEqualTo(STOCK - success.get());         // 일관성

        if (expectFullSellOut) {
            assertThat(success.get()).isEqualTo(STOCK);                 // 완판
            assertThat(finalQty).isZero();
        }
    }

    private void reset() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL); Statement s = c.createStatement()) {
            s.execute("DELETE FROM stock");
            s.execute("INSERT INTO stock VALUES (1, " + STOCK + ", 0)");
        }
    }

    private int currentQuantity() throws SQLException {
        try (Connection c = DriverManager.getConnection(URL);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT quantity FROM stock WHERE id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
