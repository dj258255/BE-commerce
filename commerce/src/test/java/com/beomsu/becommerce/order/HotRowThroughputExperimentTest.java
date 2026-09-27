package com.beomsu.becommerce.order;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 상품 재고 한 행이 초당 몇 건까지 버티는가(#384). <b>실제 MySQL(InnoDB)</b>에서 잰다.
 *
 * <p>{@link StockLockComparisonMySqlTest}는 재고 20개를 다 파는 시간을 쟀다. 여기서는 재고를 넉넉히 두고 고정 시간 동안
 * 성공한 트랜잭션 수로 처리량을 잰다. 결제 확정({@code CheckoutTx.settle})은 재고 행을 조건부 UPDATE 로 잠근 뒤 같은
 * 트랜잭션에서 주문 상태 변경 · 포인트 적립 · 이벤트 발행 기록을 쓰고 커밋한다. 재고 행 잠금은 커밋까지 풀리지 않으므로
 * 그 세 쓰기를 같은 개수로 흉내 낸다.
 *
 * <ul>
 *   <li>A. 지금 순서: 재고 UPDATE → 뒤따르는 쓰기 3건 → 커밋</li>
 *   <li>B. 순서만 바꿈: 쓰기 3건 → 재고 UPDATE → 커밋(잠금은 UPDATE 부터 커밋까지만)</li>
 *   <li>C4 · C16. 재고를 4 · 16 칸으로 나누고 임의 칸부터 시도, 비면 다음 칸(순서는 A 와 같음)</li>
 * </ul>
 *
 * <p>잠금 유지 시간은 재고 UPDATE 가 돌아온 순간(잠금을 얻은 뒤)부터 커밋이 끝날 때까지로 잰다. 한 행의 처리량 상한은
 * 대략 1초 ÷ 이 시간이다. 결과는 {@code HOTROW ...} 한 줄씩 콘솔에 남긴다.
 *
 * <p>{@code ./gradlew experimentTest --tests '*HotRowThroughput*'} 로 실행한다(약 10분, Docker 필요).
 */
@Tag("experiment")
class HotRowThroughputExperimentTest {

    private static final int[] CONCURRENCY = {1, 4, 8, 16, 20, 64};
    private static final int POOL = 80;
    private static final long WARMUP_MS = 2_000;
    private static final long RUN_MS = 5_000;
    private static final int ROUNDS = 3;
    private static final int BIG_STOCK = 10_000_000;
    private static final int SELL_OUT_STOCK = 200;
    private static final String PAYLOAD = "x".repeat(400);   // 이벤트 발행 기록의 직렬화된 본문 크기 흉내

    private static HikariDataSource ds;

    enum Shape {
        A(1, false), B(1, true), C4(4, false), C16(16, false);

        final int buckets;
        final boolean updateLast;

        Shape(int buckets, boolean updateLast) {
            this.buckets = buckets;
            this.updateLast = updateLast;
        }
    }

    @BeforeAll
    static void startDb() throws SQLException {
        String url = SharedContainers.freshDatabase("hotrow");
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(SharedContainers.username());
        cfg.setPassword(SharedContainers.password());
        cfg.setMaximumPoolSize(POOL);
        cfg.setMinimumIdle(POOL);
        ds = new HikariDataSource(cfg);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE stock_bucket (product_id BIGINT NOT NULL, bucket INT NOT NULL, quantity INT NOT NULL,"
                    + " PRIMARY KEY (product_id, bucket)) ENGINE=InnoDB");
            s.execute("CREATE TABLE orders_sim (id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL, updated_at TIMESTAMP(6) NULL)"
                    + " ENGINE=InnoDB");
            s.execute("CREATE TABLE point_ledger_sim (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,"
                    + " amount BIGINT NOT NULL, order_ref VARCHAR(40) NOT NULL) ENGINE=InnoDB");
            s.execute("CREATE TABLE event_pub_sim (id BIGINT AUTO_INCREMENT PRIMARY KEY, listener VARCHAR(120) NOT NULL,"
                    + " payload TEXT NOT NULL, published_at TIMESTAMP(6) NOT NULL) ENGINE=InnoDB");
            for (int i = 0; i < POOL; i++) {
                s.execute("INSERT INTO orders_sim VALUES (" + i + ", 'IN_PROGRESS', NULL)");
            }
            try (ResultSet rs = s.executeQuery("SELECT VERSION(), @@innodb_flush_log_at_trx_commit")) {
                rs.next();
                System.out.printf("%n=== MySQL %s · flush_log_at_trx_commit=%s · 풀 %d ===%n", rs.getString(1), rs.getString(2), POOL);
            }
        }
    }

    @AfterAll
    static void stopDb() {
        if (ds != null) ds.close();
    }

    @Test
    @DisplayName("모양 · 동시성별 초당 처리량과 잠금 유지 시간")
    void throughputByShapeAndConcurrency() throws Exception {
        for (Shape shape : Shape.values()) {
            for (int conc : CONCURRENCY) {
                reset(shape, BIG_STOCK);
                runFor(shape, conc, WARMUP_MS);
                List<Cell> rounds = new ArrayList<>();
                for (int r = 0; r < ROUNDS; r++) {
                    rounds.add(runFor(shape, conc, RUN_MS));
                }
                rounds.sort((x, y) -> Double.compare(x.tps, y.tps));
                Cell mid = rounds.get(ROUNDS / 2);
                System.out.printf("HOTROW shape=%s conc=%d tps=%.0f tps_min=%.0f tps_max=%.0f p50_ms=%.2f p99_ms=%.2f"
                                + " hold_p50_ms=%.3f hold_p99_ms=%.3f errors=%d%n",
                        shape, conc, mid.tps, rounds.get(0).tps, rounds.get(ROUNDS - 1).tps,
                        mid.p50, mid.p99, mid.holdP50, mid.holdP99, mid.errors);
                assertThat(mid.errors).isZero();
            }
        }
    }

    @Test
    @DisplayName("완판 검사: 어떤 모양이든 초과 판매 0, 팔지 못한 재고 0")
    void sellOutIsExact() throws Exception {
        for (Shape shape : Shape.values()) {
            reset(shape, SELL_OUT_STOCK);
            int threads = 64;
            AtomicInteger sold = new AtomicInteger();
            AtomicInteger attempts = new AtomicInteger();
            AtomicInteger deadlocks = new AtomicInteger();
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int worker = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    while (true) {
                        Attempt a;
                        try {
                            a = checkout(shape, worker);
                        } catch (SQLException e) {
                            // 칸을 돌며 시도하면 빈 칸의 잠금을 쥔 채 다음 칸으로 가므로 시작 칸이 다른 두 트랜잭션이
                            // 서로를 기다릴 수 있다. 칸 나누기의 대가로 세고 다시 시도한다
                            if (e.getErrorCode() != 1213) throw e;
                            deadlocks.incrementAndGet();
                            continue;
                        }
                        attempts.addAndGet(a.updates);
                        if (!a.sold) return null;   // 모든 칸이 비었다
                        sold.incrementAndGet();
                    }
                }));
            }
            start.countDown();
            for (Future<?> f : futures) f.get(60, TimeUnit.SECONDS);
            pool.shutdown();
            int left = remaining();
            System.out.printf("HOTROW-SELLOUT shape=%s sold=%d left=%d updates_per_sale=%.2f deadlocks=%d%n",
                    shape, sold.get(), left, attempts.get() / (double) sold.get(), deadlocks.get());
            assertThat(sold.get()).isEqualTo(SELL_OUT_STOCK);   // 초과 판매 0, 팔지 못한 재고 0
            assertThat(left).isZero();
        }
    }

    // --- 한 건의 결제 확정 ---

    private record Attempt(boolean sold, int updates, long holdNanos) {
    }

    /** 결제 확정 트랜잭션 하나. 재고를 못 빼면 롤백하고 sold=false. */
    private Attempt checkout(Shape shape, int worker) throws SQLException {
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            try {
                if (shape.updateLast) {
                    followingWrites(c, worker);
                }
                int start = shape.buckets == 1 ? 0 : ThreadLocalRandom.current().nextInt(shape.buckets);
                int updates = 0;
                boolean sold = false;
                for (int i = 0; i < shape.buckets && !sold; i++) {
                    updates++;
                    sold = deductOne(c, (start + i) % shape.buckets);
                }
                long locked = System.nanoTime();
                if (!sold) {
                    c.rollback();
                    return new Attempt(false, updates, 0);
                }
                if (!shape.updateLast) {
                    followingWrites(c, worker);
                }
                c.commit();
                return new Attempt(true, updates, System.nanoTime() - locked);
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private boolean deductOne(Connection c, int bucket) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE stock_bucket SET quantity = quantity - 1 WHERE product_id = 1 AND bucket = ? AND quantity >= 1")) {
            ps.setInt(1, bucket);
            return ps.executeUpdate() == 1;
        }
    }

    /** settle 에서 재고 차감 뒤에 오는 쓰기 셋: 주문 상태 변경 · 포인트 적립 · 이벤트 발행 기록. */
    private void followingWrites(Connection c, int worker) throws SQLException {
        try (PreparedStatement order = c.prepareStatement("UPDATE orders_sim SET status = 'PAID', updated_at = NOW(6) WHERE id = ?");
             PreparedStatement point = c.prepareStatement("INSERT INTO point_ledger_sim (user_id, amount, order_ref) VALUES (?, 100, ?)");
             PreparedStatement event = c.prepareStatement("INSERT INTO event_pub_sim (listener, payload, published_at) VALUES (?, ?, NOW(6))")) {
            order.setLong(1, worker);
            order.executeUpdate();
            point.setLong(1, worker);
            point.setString(2, "ORD-" + worker);
            point.executeUpdate();
            event.setString(1, "order.PaymentCompletedListener");
            event.setString(2, PAYLOAD);
            event.executeUpdate();
        }
    }

    // --- 측정 ---

    private record Cell(double tps, double p50, double p99, double holdP50, double holdP99, int errors) {
    }

    private Cell runFor(Shape shape, int conc, long durationMs) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(conc);
        AtomicBoolean stop = new AtomicBoolean();
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger errors = new AtomicInteger();
        List<Future<long[][]>> futures = new ArrayList<>();
        for (int t = 0; t < conc; t++) {
            int worker = t;
            futures.add(pool.submit(() -> {
                long[] lat = new long[200_000];
                long[] hold = new long[200_000];
                int n = 0;
                start.await();
                while (!stop.get() && n < lat.length) {
                    long began = System.nanoTime();
                    try {
                        Attempt a = checkout(shape, worker);
                        if (a.sold) {
                            lat[n] = System.nanoTime() - began;
                            hold[n] = a.holdNanos;
                            n++;
                        }
                    } catch (SQLException e) {
                        errors.incrementAndGet();   // 재고가 넉넉해 칸을 돌 일이 없으니 여기서는 0 이어야 한다
                    }
                }
                return new long[][]{Arrays.copyOf(lat, n), Arrays.copyOf(hold, n)};
            }));
        }
        long began = System.nanoTime();
        start.countDown();
        Thread.sleep(durationMs);
        stop.set(true);
        long elapsed = System.nanoTime() - began;
        List<Long> lats = new ArrayList<>();
        List<Long> holds = new ArrayList<>();
        for (Future<long[][]> f : futures) {
            long[][] r = f.get(60, TimeUnit.SECONDS);
            for (long v : r[0]) lats.add(v);
            for (long v : r[1]) holds.add(v);
        }
        pool.shutdown();
        // 멈춤 신호 뒤에 끝난 건까지 세므로 경과 시간도 마지막 건이 끝난 때까지로 잡는다
        long total = System.nanoTime() - began;
        double tps = lats.size() / (Math.max(elapsed, total) / 1e9);
        return new Cell(tps, pct(lats, 50), pct(lats, 99), pct(holds, 50), pct(holds, 99), errors.get());
    }

    private static double pct(List<Long> nanos, int p) {
        if (nanos.isEmpty()) return Double.NaN;
        long[] a = nanos.stream().mapToLong(Long::longValue).sorted().toArray();
        return a[Math.min(a.length - 1, (int) Math.ceil(p / 100.0 * a.length) - 1)] / 1e6;
    }

    private void reset(Shape shape, int stock) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM stock_bucket");
            s.execute("TRUNCATE point_ledger_sim");
            s.execute("TRUNCATE event_pub_sim");
            int base = stock / shape.buckets;
            for (int b = 0; b < shape.buckets; b++) {
                int q = base + (b < stock % shape.buckets ? 1 : 0);
                s.execute("INSERT INTO stock_bucket VALUES (1, " + b + ", " + q + ")");
            }
        }
    }

    private int remaining() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(SUM(quantity), 0) FROM stock_bucket WHERE product_id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }
}
