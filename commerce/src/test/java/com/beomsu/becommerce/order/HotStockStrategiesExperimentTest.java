package com.beomsu.becommerce.order;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 상품 재고를 초당 1,000건으로 올리려면 무엇을 버리나(#472). <b>실제 MySQL 8.4 · Redis 7.4</b>에서 잰다.
 *
 * <p>{@link HotRowThroughputExperimentTest}(#384)의 결제 확정 흉내를 그대로 쓴다. 재고 차감 뒤 같은 트랜잭션에서
 * 주문 상태 변경 · 포인트 적립 · 이벤트 발행 기록 세 건을 쓴다. 포인트 적립 행이 곧 "팔린 주문" 기록이다
 * (주문 하나에 한 행, {@code order_ref} 유니크). 바꾸는 것은 재고를 빼는 방식뿐이다.
 *
 * <ul>
 *   <li>ROW: 한 행 조건부 UPDATE(지금 운영 방식)</li>
 *   <li>UNIT_RR · UNIT_RC: 판매 단위마다 행, {@code FOR UPDATE SKIP LOCKED} 로 하나 집음. 가용 행은 1,000개 한도로
 *       카운터 행에서 채움(Shopify 방식). 격리 수준 REPEATABLE READ · READ COMMITTED</li>
 *   <li>REDIS: Redis Lua 로 빼고 조작 번호를 목록에 남김. DB 재고는 반영 작업이 묶어서 뺌(京东 방식)</li>
 *   <li>BATCH_W5 · BATCH_W20 · BATCH_W5_SP: 요청을 큐에 모아 커밋 스레드 하나가 창(5 · 20ms, 최대 64건)마다 한
 *       트랜잭션으로 처리(합병 차감). SP 는 요청마다 SAVEPOINT 를 둠</li>
 * </ul>
 *
 * <p>결과는 {@code HOTSTOCK-*} 한 줄씩 콘솔에 남긴다. {@code ./gradlew experimentTest --tests '*HotStockStrategies*'}
 * (약 15분, Docker 필요).
 */
@Tag("experiment")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HotStockStrategiesExperimentTest {

    private static final int[] CONCURRENCY = {1, 20, 64};
    private static final int POOL = 90;
    private static final long WARMUP_MS = 2_000;
    private static final long RUN_MS = 5_000;
    private static final int ROUNDS = 3;
    private static final int BIG_STOCK = 10_000_000;
    private static final int SELL_OUT_STOCK = 200;
    private static final int UNIT_POOL_CAP = 1_000;
    private static final int BATCH_MAX = 64;
    private static final String PAYLOAD = "x".repeat(400);
    private static final AtomicLong GLOBAL_SEQ = new AtomicLong();

    private static HikariDataSource ds;
    private static final GenericContainer<?> REDIS_CONTAINER = SharedContainers.redis();
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;

    private static final String K_STOCK = "hs:stock";
    private static final String K_OPS = "hs:ops";
    private static final String K_PROCESSING = "hs:processing";
    private static final String K_SEQ = "hs:seq";

    /** 재고가 있으면 1 빼고 조작 번호를 목록 끝에 넣는다. 없으면 -1. */
    private static final DefaultRedisScript<Long> DEDUCT = new DefaultRedisScript<>("""
            local s = tonumber(redis.call('GET', KEYS[1]) or '0')
            if s < 1 then return -1 end
            redis.call('DECR', KEYS[1])
            local id = redis.call('INCR', KEYS[3])
            redis.call('RPUSH', KEYS[2], id)
            return id
            """, Long.class);

    /** 목록 앞에서 최대 n 개를 꺼낸다(되돌릴 곳 없음). */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> POP_N = new DefaultRedisScript<>("""
            local items = redis.call('LRANGE', KEYS[1], 0, tonumber(ARGV[1]) - 1)
            if #items > 0 then redis.call('LTRIM', KEYS[1], #items, -1) end
            return items
            """, List.class);

    /** 목록 앞에서 최대 n 개를 처리 중 목록으로 옮긴다(반영 작업이 죽어도 남는다). */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> MOVE_N = new DefaultRedisScript<>("""
            local items = {}
            for i = 1, tonumber(ARGV[1]) do
              local v = redis.call('LMOVE', KEYS[1], KEYS[2], 'LEFT', 'RIGHT')
              if not v then break end
              items[#items + 1] = v
            end
            return items
            """, List.class);

    /** 대조용: Redis 재고와 아직 DB 에 반영되지 않은 조작 수를 한 번에 읽는다. */
    @SuppressWarnings("rawtypes")
    private static final DefaultRedisScript<List> SNAPSHOT = new DefaultRedisScript<>("""
            return {tonumber(redis.call('GET', KEYS[1]) or '0'), redis.call('LLEN', KEYS[2]), redis.call('LLEN', KEYS[3])}
            """, List.class);

    enum Strategy { ROW, UNIT_RR, UNIT_RC, REDIS, BATCH_W5, BATCH_W20, BATCH_W5_SP }

    enum Outcome { SOLD, SOLD_OUT }

    @BeforeAll
    static void start() throws SQLException {
        String url = SharedContainers.freshDatabase("hotstock");
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(SharedContainers.username());
        cfg.setPassword(SharedContainers.password());
        cfg.setMaximumPoolSize(POOL);
        cfg.setMinimumIdle(POOL);
        ds = new HikariDataSource(cfg);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE stock_row (product_id BIGINT PRIMARY KEY, quantity INT NOT NULL) ENGINE=InnoDB");
            s.execute("CREATE TABLE stock_unit (id BIGINT AUTO_INCREMENT PRIMARY KEY, product_id BIGINT NOT NULL,"
                    + " state TINYINT NOT NULL, order_ref VARCHAR(40) NULL, KEY idx_avail (product_id, state, id)) ENGINE=InnoDB");
            s.execute("CREATE TABLE stock_reserve (product_id BIGINT PRIMARY KEY, remaining INT NOT NULL) ENGINE=InnoDB");
            s.execute("CREATE TABLE stock_op_applied (op_id BIGINT PRIMARY KEY) ENGINE=InnoDB");
            s.execute("CREATE TABLE orders_sim (id BIGINT PRIMARY KEY, status VARCHAR(20) NOT NULL, updated_at TIMESTAMP(6) NULL)"
                    + " ENGINE=InnoDB");
            s.execute("CREATE TABLE point_ledger_sim (id BIGINT AUTO_INCREMENT PRIMARY KEY, user_id BIGINT NOT NULL,"
                    + " amount BIGINT NOT NULL, order_ref VARCHAR(40) NOT NULL, UNIQUE KEY uk_ref (order_ref)) ENGINE=InnoDB");
            s.execute("CREATE TABLE event_pub_sim (id BIGINT AUTO_INCREMENT PRIMARY KEY, listener VARCHAR(120) NOT NULL,"
                    + " payload TEXT NOT NULL, published_at TIMESTAMP(6) NOT NULL) ENGINE=InnoDB");
            for (int i = 0; i < 2_000; i++) {
                s.execute("INSERT INTO orders_sim VALUES (" + i + ", 'IN_PROGRESS', NULL)");
            }
            try (ResultSet rs = s.executeQuery("SELECT VERSION(), @@innodb_flush_log_at_trx_commit, @@transaction_isolation")) {
                rs.next();
                System.out.printf("%n=== MySQL %s · flush_log_at_trx_commit=%s · 기본 격리 %s · 풀 %d ===%n",
                        rs.getString(1), rs.getString(2), rs.getString(3), POOL);
            }
        }
        SharedContainers.flushRedis();
        redisFactory = new LettuceConnectionFactory(REDIS_CONTAINER.getHost(), REDIS_CONTAINER.getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void stop() {
        if (ds != null) ds.close();
        if (redisFactory != null) redisFactory.destroy();
    }

    // =====================================================================================================
    // 1. 처리량
    // =====================================================================================================

    @Test
    @Order(1)
    @DisplayName("방식 · 동시성별 초당 처리량과 지연")
    void throughput() throws Exception {
        for (Strategy st : Strategy.values()) {
            for (int conc : CONCURRENCY) {
                try (Seller seller = Seller.of(st, BIG_STOCK)) {
                    seller.runFor(conc, WARMUP_MS, null);
                    List<Cell> rounds = new ArrayList<>();
                    for (int r = 0; r < ROUNDS; r++) rounds.add(seller.runFor(conc, RUN_MS, null));
                    rounds.sort((a, b) -> Double.compare(a.tps, b.tps));
                    Cell mid = rounds.get(ROUNDS / 2);
                    seller.drain();
                    System.out.printf("HOTSTOCK-TPS strategy=%s conc=%d tps=%.0f tps_min=%.0f tps_max=%.0f p50_ms=%.2f"
                                    + " p99_ms=%.2f errors=%d extra=%s%n",
                            st, conc, mid.tps, rounds.get(0).tps, rounds.get(ROUNDS - 1).tps, mid.p50, mid.p99,
                            mid.errors, seller.extra());
                    assertThat(mid.errors).isZero();
                    seller.assertConsistent(BIG_STOCK);
                }
            }
        }
    }

    // =====================================================================================================
    // 2. 완판 검사와 공정성
    // =====================================================================================================

    @Test
    @Order(2)
    @DisplayName("완판 검사: 초과 판매 0, 팔지 못한 재고 0")
    void sellOut() throws Exception {
        for (Strategy st : Strategy.values()) {
            try (Seller seller = Seller.of(st, SELL_OUT_STOCK)) {
                int threads = 64;
                ExecutorService pool = Executors.newFixedThreadPool(threads);
                CountDownLatch go = new CountDownLatch(1);
                AtomicInteger seq = new AtomicInteger();
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    int worker = t;
                    fs.add(pool.submit(() -> {
                        go.await();
                        while (seller.sell(worker, "SO-" + seq.incrementAndGet()) == Outcome.SOLD) {
                            // 매진까지 계속 산다
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
                pool.shutdown();
                seller.drain();
                int sold = countSold();
                int left = seller.remainingTruthSource();
                System.out.printf("HOTSTOCK-SELLOUT strategy=%s sold=%d left=%d oversell=%d extra=%s%n",
                        st, sold, left, Math.max(0, sold - SELL_OUT_STOCK), seller.extra());
                assertThat(sold).isEqualTo(SELL_OUT_STOCK);
                assertThat(left).isZero();
                seller.assertConsistent(SELL_OUT_STOCK);
            }
        }
    }

    @Test
    @Order(3)
    @DisplayName("공정성: 고객 1,000명이 0.5초에 걸쳐 차례로 한 번씩, 먼저 온 200명 중 산 사람")
    void fairness() throws Exception {
        int customers = 1_000;
        long spacingMicros = 500;
        for (Strategy st : Strategy.values()) {
            try (Seller seller = Seller.of(st, SELL_OUT_STOCK)) {
                ScheduledExecutorService clock = Executors.newScheduledThreadPool(1);
                ExecutorService workers = Executors.newFixedThreadPool(200);
                boolean[] bought = new boolean[customers];
                CountDownLatch done = new CountDownLatch(customers);
                long base = System.nanoTime() + 50_000_000L;
                for (int i = 0; i < customers; i++) {
                    int idx = i;
                    long at = base + idx * spacingMicros * 1_000L;
                    clock.schedule(() -> workers.submit(() -> {
                        try {
                            bought[idx] = seller.sell(idx % 2_000, "FA-" + idx) == Outcome.SOLD;
                        } catch (Exception e) {
                            System.out.println("HOTSTOCK-FAIR error " + e);
                        } finally {
                            done.countDown();
                        }
                    }), at - System.nanoTime(), TimeUnit.NANOSECONDS);
                }
                assertThat(done.await(180, TimeUnit.SECONDS)).isTrue();
                clock.shutdown();
                workers.shutdown();
                seller.drain();
                int firstComers = 0;
                int total = 0;
                for (int i = 0; i < customers; i++) {
                    if (bought[i]) {
                        total++;
                        if (i < SELL_OUT_STOCK) firstComers++;
                    }
                }
                System.out.printf("HOTSTOCK-FAIR strategy=%s sold=%d first_200_bought=%d%n", st, total, firstComers);
                assertThat(total).isEqualTo(SELL_OUT_STOCK);
            }
        }
    }

    // =====================================================================================================
    // 3. REDIS 방식 장애 주입
    // =====================================================================================================

    @Test
    @Order(4)
    @DisplayName("REDIS: 반영 작업이 죽는 자리 × 반영 방식별 손실 · 중복")
    void redisApplierCrash() throws Exception {
        for (ApplyMode mode : ApplyMode.values()) {
            for (CrashPoint point : new CrashPoint[]{CrashPoint.BEFORE_DB, CrashPoint.AFTER_DB}) {
                int stock = 5_000;
                try (RedisSeller seller = new RedisSeller(stock, mode)) {
                    AtomicInteger seq = new AtomicInteger();
                    AtomicBoolean crashed = new AtomicBoolean();
                    int crashedBatch = runUntilSoldOut(seller, 20, seq, () -> {
                        if (seq.get() >= 1_000 && crashed.compareAndSet(false, true)) {
                            return seller.crashOnceAndRestart(point);
                        }
                        return -1;
                    });
                    seller.drain();
                    int sold = countSold();
                    int db = dbRowStock();
                    int truthLeft = stock - sold;
                    System.out.printf("HOTSTOCK-REDIS-CRASH mode=%s crash=%s crashed_batch=%d sold=%d oversell=%d db_stock=%d"
                                    + " truth_left=%d db_error=%d(%s)%n",
                            mode, point, crashedBatch, sold, Math.max(0, sold - stock), db, truthLeft, db - truthLeft,
                            db > truthLeft ? "차감 손실" : db < truthLeft ? "중복 차감" : "일치");
                }
            }
        }
    }

    @Test
    @Order(5)
    @DisplayName("REDIS: Redis 데이터 유실 뒤 복구 방식별 초과 판매")
    void redisDataLoss() throws Exception {
        for (long lagMs : new long[]{0, 500})
        for (Recovery rec : Recovery.values()) {
            int stock = 5_000;
            try (RedisSeller seller = new RedisSeller(stock, ApplyMode.RELIABLE_IDEMPOTENT)) {
                AtomicInteger seq = new AtomicInteger();
                AtomicBoolean lost = new AtomicBoolean();
                AtomicLong pauseMicros = new AtomicLong();
                AtomicInteger pendingAtLoss = new AtomicInteger();
                runUntilSoldOut(seller, 20, seq, () -> {
                    if (seq.get() >= 1_000 && lost.compareAndSet(false, true)) {
                        long t0 = System.nanoTime();
                        pendingAtLoss.set(seller.loseRedisAndRecover(rec, lagMs));
                        pauseMicros.set((System.nanoTime() - t0) / 1_000);
                    }
                    return -1;
                });
                seller.drain();
                int sold = countSold();
                System.out.printf("HOTSTOCK-REDIS-LOSS applier_lag_ms=%d recovery=%s pending_at_loss=%d stop_ms=%.1f sold=%d oversell=%d"
                                + " db_stock=%d truth_left=%d%n",
                        lagMs, rec, pendingAtLoss.get(), pauseMicros.get() / 1000.0, sold, Math.max(0, sold - stock),
                        dbRowStock(), Math.max(0, stock - sold));
            }
        }
    }

    @Test
    @Order(6)
    @DisplayName("REDIS: 대조(Redis 재고 + 미반영 조작 = DB 재고)의 오탐과 탐지 시간")
    void redisReconciliation() throws Exception {
        // (1) 장애 없이 동시성 64로 5초: 한 번 어긋나면 경보 대 세 번 연속 어긋나면 경보
        try (RedisSeller seller = new RedisSeller(BIG_STOCK, ApplyMode.RELIABLE_IDEMPOTENT)) {
            Reconciler rc = new Reconciler(100);
            rc.start();
            seller.runFor(64, RUN_MS, null);
            rc.stop();
            System.out.printf("HOTSTOCK-RECON clean samples=%d mismatch_samples=%d alarms_single=%d alarms_3x=%d%n",
                    rc.samples.get(), rc.mismatches.get(), rc.alarmsSingle.get(), rc.alarms3x.get());
            seller.drain();
        }
        // (2) 되돌릴 곳 없는 반영 작업이 꺼낸 묶음을 DB 에 쓰기 전에 죽는다: 영구 어긋남을 몇 ms 만에 잡나
        try (RedisSeller seller = new RedisSeller(BIG_STOCK, ApplyMode.SIMPLE)) {
            Reconciler rc = new Reconciler(100);
            rc.start();
            AtomicLong injectedAt = new AtomicLong();
            AtomicBoolean done = new AtomicBoolean();
            seller.runFor(64, RUN_MS, () -> {
                if (done.compareAndSet(false, true)) {
                    injectedAt.set(System.nanoTime());
                    seller.crashOnceAndRestart(CrashPoint.BEFORE_DB);
                }
            });
            Thread.sleep(1_500);
            rc.stop();
            long single = rc.firstAlarmSingleAfter(injectedAt.get());
            long triple = rc.firstAlarm3xAfter(injectedAt.get());
            System.out.printf("HOTSTOCK-RECON injected detect_single_ms=%.0f detect_3x_ms=%.0f alarms_single=%d alarms_3x=%d%n",
                    single < 0 ? -1 : single / 1e6, triple < 0 ? -1 : triple / 1e6, rc.alarmsSingle.get(), rc.alarms3x.get());
            seller.drain();
        }
    }

    // =====================================================================================================
    // 4. BATCH 방식 한 건 실패의 파급
    // =====================================================================================================

    @Test
    @Order(7)
    @DisplayName("BATCH: 묶음 안 한 건이 실패할 때 함께 되돌아간 무관한 주문")
    void batchPoison() throws Exception {
        for (Strategy st : new Strategy[]{Strategy.BATCH_W5, Strategy.BATCH_W5_SP}) {
            try (BatchSeller seller = new BatchSeller(BIG_STOCK, 5, st == Strategy.BATCH_W5_SP)) {
                int conc = 64;
                int perWorker = 100;
                ExecutorService pool = Executors.newFixedThreadPool(conc);
                AtomicInteger poisonFailed = new AtomicInteger();
                AtomicInteger innocentFailed = new AtomicInteger();
                AtomicInteger innocentSold = new AtomicInteger();
                CountDownLatch go = new CountDownLatch(1);
                List<Future<?>> fs = new ArrayList<>();
                for (int t = 0; t < conc; t++) {
                    int worker = t;
                    fs.add(pool.submit(() -> {
                        go.await();
                        for (int i = 0; i < perWorker; i++) {
                            // 요청 100건에 1건. 스레드마다 다른 걸음에 넣는다: 같은 걸음에 넣으면 64스레드가 나란히 가다
                            // 독 요청끼리 한 묶음이 되어 함께 되돌아갈 무관한 주문이 없어진다(첫 실행에서 실제로 그랬다)
                            boolean poison = i == worker % perWorker;
                            String ref = poison ? "POISON-" + "p".repeat(60) + worker : "BP-" + worker + "-" + i;
                            try {
                                seller.sell(worker, ref);
                                if (!poison) innocentSold.incrementAndGet();
                            } catch (SQLException e) {
                                if (poison) poisonFailed.incrementAndGet();
                                else innocentFailed.incrementAndGet();
                            }
                        }
                        return null;
                    }));
                }
                go.countDown();
                for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
                pool.shutdown();
                System.out.printf("HOTSTOCK-POISON strategy=%s requests=%d poison=%d poison_failed=%d innocent_failed=%d"
                                + " innocent_sold=%d batches=%d avg_batch=%.1f%n",
                        st, conc * perWorker, conc, poisonFailed.get(), innocentFailed.get(), innocentSold.get(),
                        seller.batches.get(), seller.items.get() / (double) Math.max(1, seller.batches.get()));
                seller.assertConsistent(BIG_STOCK);
            }
        }
    }

    // =====================================================================================================
    // 판매 방식
    // =====================================================================================================

    abstract static class Seller implements AutoCloseable {
        static Seller of(Strategy st, int stock) throws SQLException {
            resetCommon();
            return switch (st) {
                case ROW -> new RowSeller(stock);
                case UNIT_RR -> new UnitSeller(stock, Connection.TRANSACTION_REPEATABLE_READ);
                case UNIT_RC -> new UnitSeller(stock, Connection.TRANSACTION_READ_COMMITTED);
                case REDIS -> new RedisSeller(stock, ApplyMode.RELIABLE_IDEMPOTENT);
                case BATCH_W5 -> new BatchSeller(stock, 5, false);
                case BATCH_W20 -> new BatchSeller(stock, 20, false);
                case BATCH_W5_SP -> new BatchSeller(stock, 5, true);
            };
        }

        abstract Outcome sell(int worker, String orderRef) throws SQLException;

        /** 진실 원천 기준 남은 재고. */
        abstract int remainingTruthSource() throws SQLException;

        /** 비동기로 남은 일이 있으면 끝날 때까지 기다린다. */
        void drain() throws Exception {
        }

        String extra() {
            return "-";
        }

        /** 팔린 주문 수 + 남은 재고 = 처음 재고. */
        void assertConsistent(int initial) throws Exception {
            assertThat(countSold() + remainingTruthSource()).isEqualTo(initial);
        }

        Cell runFor(int conc, long durationMs, Runnable midInjection) throws Exception {
            ExecutorService pool = Executors.newFixedThreadPool(conc);
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch go = new CountDownLatch(1);
            AtomicInteger errors = new AtomicInteger();
            AtomicLong seq = GLOBAL_SEQ;   // 반복 측정 사이에도 주문 번호가 겹치지 않게
            List<Future<long[]>> fs = new ArrayList<>();
            for (int t = 0; t < conc; t++) {
                int worker = t;
                fs.add(pool.submit(() -> {
                    long[] lat = new long[400_000];
                    int n = 0;
                    go.await();
                    while (!stop.get() && n < lat.length) {
                        long began = System.nanoTime();
                        try {
                            if (sell(worker, "T-" + worker + "-" + seq.incrementAndGet()) == Outcome.SOLD) {
                                lat[n++] = System.nanoTime() - began;
                            }
                        } catch (SQLException e) {
                            errors.incrementAndGet();
                        }
                    }
                    return Arrays.copyOf(lat, n);
                }));
            }
            long began = System.nanoTime();
            go.countDown();
            if (midInjection != null) {
                Thread.sleep(durationMs / 2);
                midInjection.run();
                Thread.sleep(durationMs - durationMs / 2);
            } else {
                Thread.sleep(durationMs);
            }
            stop.set(true);
            List<long[]> parts = new ArrayList<>();
            for (Future<long[]> f : fs) parts.add(f.get(60, TimeUnit.SECONDS));
            long total = System.nanoTime() - began;
            pool.shutdown();
            long[] all = parts.stream().flatMapToLong(Arrays::stream).sorted().toArray();
            return new Cell(all.length / (total / 1e9), pct(all, 50), pct(all, 99), errors.get());
        }

        @Override
        public void close() throws Exception {
        }
    }

    /** ROW: 한 행 조건부 UPDATE, 잠금은 커밋까지. */
    static final class RowSeller extends Seller {
        RowSeller(int stock) throws SQLException {
            setRowStock(stock);
        }

        @Override
        Outcome sell(int worker, String orderRef) throws SQLException {
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    if (!deductRow(c, 1)) {
                        c.rollback();
                        return Outcome.SOLD_OUT;
                    }
                    followingWrites(c, worker, orderRef);
                    c.commit();
                    return Outcome.SOLD;
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }

        @Override
        int remainingTruthSource() throws SQLException {
            return dbRowStock();
        }
    }

    /** UNIT: 단위당 행 + SKIP LOCKED, 가용 행 1,000개 한도로 카운터에서 채움. */
    static final class UnitSeller extends Seller {
        private final int isolation;
        private final AtomicInteger refills = new AtomicInteger();
        private final AtomicInteger emptyButLocked = new AtomicInteger();
        private final AtomicInteger deadlocks = new AtomicInteger();
        private final AtomicInteger lockWaitTimeouts = new AtomicInteger();

        UnitSeller(int stock, int isolation) throws SQLException {
            this.isolation = isolation;
            try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                s.execute("TRUNCATE stock_unit");
                s.execute("DELETE FROM stock_reserve");
                int initial = Math.min(stock, UNIT_POOL_CAP);
                s.execute("INSERT INTO stock_reserve VALUES (1, " + (stock - initial) + ")");
                insertUnits(c, initial);
            }
        }

        @Override
        Outcome sell(int worker, String orderRef) throws SQLException {
            while (true) {
                try {
                    Outcome o = trySell(worker, orderRef);
                    if (o != null) return o;
                } catch (SQLException e) {
                    if (e.getErrorCode() == 1213) {
                        deadlocks.incrementAndGet();
                        continue;
                    }
                    if (e.getErrorCode() == 1205) {
                        lockWaitTimeouts.incrementAndGet();
                        continue;
                    }
                    throw e;
                }
                // 가용 행이 비어 보였다: 채우거나, 정말 매진인지 본다
                if (refill() > 0) continue;
                if (availableCount() == 0) return Outcome.SOLD_OUT;
                emptyButLocked.incrementAndGet();   // 남은 행이 다른 트랜잭션에 잠겨 있을 뿐이다
                Thread.onSpinWait();
            }
        }

        /** 집으면 SOLD, 집을 행이 안 보이면 null. */
        private Outcome trySell(int worker, String orderRef) throws SQLException {
            try (Connection c = ds.getConnection()) {
                c.setTransactionIsolation(isolation);
                c.setAutoCommit(false);
                try {
                    long id;
                    try (PreparedStatement ps = c.prepareStatement("SELECT id FROM stock_unit WHERE product_id = 1 AND state = 0"
                            + " ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED");
                         ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) {
                            c.rollback();
                            return null;
                        }
                        id = rs.getLong(1);
                    }
                    try (PreparedStatement ps = c.prepareStatement("UPDATE stock_unit SET state = 1, order_ref = ? WHERE id = ?")) {
                        ps.setString(1, orderRef.length() > 40 ? orderRef.substring(0, 40) : orderRef);
                        ps.setLong(2, id);
                        ps.executeUpdate();
                    }
                    followingWrites(c, worker, orderRef);
                    c.commit();
                    return Outcome.SOLD;
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                }
            }
        }

        /** 카운터 행을 잠그고 가용 행을 한도까지 채운다. 채운 개수. */
        private int refill() throws SQLException {
            try (Connection c = ds.getConnection()) {
                c.setTransactionIsolation(isolation);
                c.setAutoCommit(false);
                try {
                    int remaining;
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT remaining FROM stock_reserve WHERE product_id = 1 FOR UPDATE")) {
                        rs.next();
                        remaining = rs.getInt(1);
                    }
                    if (remaining == 0) {
                        c.rollback();
                        return 0;
                    }
                    // 카운터 행을 쥔 채로 센다. 먼저 채운 스레드가 있으면 채우지 않고 다시 집으러 간다(한도 1,000 유지)
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM stock_unit WHERE product_id = 1 AND state = 0")) {
                        rs.next();
                        if (rs.getInt(1) > 0) {
                            c.rollback();
                            return 1;
                        }
                    }
                    int k = Math.min(remaining, UNIT_POOL_CAP);
                    insertUnits(c, k);
                    try (Statement s = c.createStatement()) {
                        s.executeUpdate("UPDATE stock_reserve SET remaining = remaining - " + k + " WHERE product_id = 1");
                    }
                    c.commit();
                    refills.incrementAndGet();
                    return k;
                } catch (SQLException e) {
                    c.rollback();
                    if (e.getErrorCode() == 1213 || e.getErrorCode() == 1205) return 1;   // 다시 시도하게 한다
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                    c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                }
            }
        }

        private static void insertUnits(Connection c, int k) throws SQLException {
            int chunk = 1_000;
            for (int done = 0; done < k; done += chunk) {
                int n = Math.min(chunk, k - done);
                StringBuilder sb = new StringBuilder("INSERT INTO stock_unit (product_id, state) VALUES ");
                for (int i = 0; i < n; i++) sb.append(i == 0 ? "" : ",").append("(1,0)");
                try (Statement s = c.createStatement()) {
                    s.executeUpdate(sb.toString());
                }
            }
        }

        private int availableCount() throws SQLException {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM stock_unit WHERE product_id = 1 AND state = 0")) {
                rs.next();
                return rs.getInt(1);
            }
        }

        @Override
        int remainingTruthSource() throws SQLException {
            try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT (SELECT COUNT(*) FROM stock_unit WHERE product_id = 1 AND state = 0)"
                         + " + (SELECT remaining FROM stock_reserve WHERE product_id = 1)")) {
                rs.next();
                return rs.getInt(1);
            }
        }

        @Override
        String extra() {
            return "refills=" + refills.get() + ",empty_but_locked=" + emptyButLocked.get() + ",deadlocks=" + deadlocks.get()
                    + ",lock_wait_timeouts=" + lockWaitTimeouts.get();
        }
    }

    enum ApplyMode { SIMPLE, RELIABLE, RELIABLE_IDEMPOTENT }

    enum CrashPoint { NONE, BEFORE_DB, AFTER_DB }

    enum Recovery { FROM_DB_STOCK, FROM_ORDERS }

    /** REDIS: Redis 에서 빼고, DB 재고는 반영 작업이 조작 목록을 묶어 뺀다. */
    static final class RedisSeller extends Seller {
        private final int initial;
        private final ApplyMode mode;
        private final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
        private final AtomicReference<Applier> applier = new AtomicReference<>();
        private final AtomicInteger appliedBatches = new AtomicInteger();

        RedisSeller(int stock, ApplyMode mode) throws SQLException {
            resetCommon();
            this.initial = stock;
            this.mode = mode;
            setRowStock(stock);
            redis.delete(List.of(K_STOCK, K_OPS, K_PROCESSING, K_SEQ));
            redis.opsForValue().set(K_STOCK, String.valueOf(stock));
            startApplier(CrashPoint.NONE);
        }

        @Override
        Outcome sell(int worker, String orderRef) throws SQLException {
            gate.readLock().lock();
            try {
                Long id = redis.execute(DEDUCT, List.of(K_STOCK, K_OPS, K_SEQ));
                if (id == null || id < 0) return Outcome.SOLD_OUT;
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    try {
                        followingWrites(c, worker, orderRef);
                        c.commit();
                        return Outcome.SOLD;
                    } catch (SQLException e) {
                        c.rollback();
                        redis.opsForValue().increment(K_STOCK);   // 보상. 조작 번호는 이미 나갔으므로 대조가 어긋남으로 볼 수 있다
                        throw e;
                    } finally {
                        c.setAutoCommit(true);
                    }
                }
            } finally {
                gate.readLock().unlock();
            }
        }

        private void startApplier(CrashPoint crash) {
            Applier a = new Applier(mode, crash);
            applier.set(a);
            a.start();
        }

        /** 지금 반영 작업을 지정한 자리에서 한 번 죽이고 새로 띄운다. 죽을 때 쥐고 있던 묶음 크기. */
        int crashOnceAndRestart(CrashPoint point) {
            Applier old = applier.get();
            old.crashAt.set(point);
            try {
                old.thread.join(10_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            int held = old.crashedBatch;
            startApplier(CrashPoint.NONE);
            return held;
        }

        /**
         * Redis 를 비운다(영속 없는 재시작과 같은 상태). 판매를 잠깐 멈추고 고른 방식으로 다시 채운다.
         * 비울 때 남아 있던 미반영 조작 수를 돌려준다.
         */
        int loseRedisAndRecover(Recovery rec, long applierLagMs) {
            // 반영 작업이 뒤처진 상태를 만든다: 반영 작업을 멈추고 그동안 판매는 계속 받는다
            Applier old = applier.get();
            old.running.set(false);
            try {
                old.thread.join(10_000);
                if (applierLagMs > 0) Thread.sleep(applierLagMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            gate.writeLock().lock();
            try {
                List<Object> snap = snapshot();
                int pending = ((Number) snap.get(1)).intValue() + ((Number) snap.get(2)).intValue();
                SharedContainers.redis().execInContainer("redis-cli", "FLUSHALL");
                // 조작 번호도 1부터 다시 시작한다. 그대로 두면 이미 반영된 번호와 겹쳐 멱등 표가 새 조작을 버린다.
                // 두 복구 모두 번호를 DB 에 남은 최댓값 위로 올리고 시작한다(복구 방식의 차이만 보려고)
                try (Connection c = ds.getConnection(); Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(op_id), 0) FROM stock_op_applied")) {
                    rs.next();
                    redis.opsForValue().set(K_SEQ, String.valueOf(rs.getLong(1)));
                }
                if (rec == Recovery.FROM_DB_STOCK) {
                    redis.opsForValue().set(K_STOCK, String.valueOf(dbRowStock()));
                } else {
                    int truth = initial - countSold();
                    try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
                        s.executeUpdate("UPDATE stock_row SET quantity = " + truth + " WHERE product_id = 1");
                    }
                    redis.opsForValue().set(K_STOCK, String.valueOf(truth));
                }
                startApplier(CrashPoint.NONE);
                return pending;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            } finally {
                gate.writeLock().unlock();
            }
        }

        @Override
        void drain() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (System.nanoTime() < deadline) {
                List<Object> snap = snapshot();
                if (((Number) snap.get(1)).intValue() == 0 && ((Number) snap.get(2)).intValue() == 0) return;
                Thread.sleep(5);
            }
            throw new IllegalStateException("반영 작업이 60초 안에 비우지 못했다");
        }

        @Override
        int remainingTruthSource() throws SQLException {
            return initial - countSold();   // 주문 기록이 진실 원천
        }

        @Override
        void assertConsistent(int initialStock) throws Exception {
            super.assertConsistent(initialStock);
            assertThat(dbRowStock()).isEqualTo(remainingTruthSource());   // 다 반영되면 DB 재고도 맞아야 한다
        }

        @Override
        String extra() {
            return "applied_batches=" + appliedBatches.get();
        }

        @Override
        public void close() throws Exception {
            Applier a = applier.get();
            if (a != null) {
                a.running.set(false);
                a.thread.join(10_000);
            }
        }

        final class Applier {
            final ApplyMode mode;
            final AtomicReference<CrashPoint> crashAt;
            final AtomicBoolean running = new AtomicBoolean(true);
            final Thread thread;
            volatile int crashedBatch = 0;

            Applier(ApplyMode mode, CrashPoint crash) {
                this.mode = mode;
                this.crashAt = new AtomicReference<>(crash);
                this.thread = new Thread(this::loop, "hotstock-applier");
                this.thread.setDaemon(true);
            }

            void start() {
                thread.start();
            }

            @SuppressWarnings("unchecked")
            private void loop() {
                try {
                    if (mode != ApplyMode.SIMPLE) {
                        // 다시 떴다: 전에 쥐고 있던 묶음이 처리 중 목록에 남아 있으면 그것부터
                        List<String> leftover = redis.opsForList().range(K_PROCESSING, 0, -1);
                        if (leftover != null && !leftover.isEmpty()) {
                            apply(leftover);
                            redis.delete(K_PROCESSING);
                        }
                    }
                    while (running.get()) {
                        List<String> ops = mode == ApplyMode.SIMPLE
                                ? (List<String>) redis.execute(POP_N, List.of(K_OPS), "200")
                                : (List<String>) redis.execute(MOVE_N, List.of(K_OPS, K_PROCESSING), "200");
                        if (ops == null || ops.isEmpty()) {
                            Thread.sleep(2);
                            continue;
                        }
                        if (crashAt.get() == CrashPoint.BEFORE_DB) {
                            crashedBatch = ops.size();
                            return;   // 꺼낸 묶음을 쥔 채 죽는다
                        }
                        apply(ops);
                        if (crashAt.get() == CrashPoint.AFTER_DB) {
                            crashedBatch = ops.size();
                            return;   // DB 에는 반영했지만 처리 중 목록을 지우지 못하고 죽는다
                        }
                        if (mode != ApplyMode.SIMPLE) redis.delete(K_PROCESSING);
                    }
                } catch (Exception e) {
                    System.out.println("HOTSTOCK applier error " + e);
                }
            }

            private void apply(List<String> ops) throws SQLException {
                try (Connection c = ds.getConnection()) {
                    c.setAutoCommit(false);
                    try {
                        int n = ops.size();
                        if (mode == ApplyMode.RELIABLE_IDEMPOTENT) {
                            StringBuilder sb = new StringBuilder("INSERT IGNORE INTO stock_op_applied (op_id) VALUES ");
                            for (int i = 0; i < ops.size(); i++) sb.append(i == 0 ? "" : ",").append('(').append(Long.parseLong(ops.get(i))).append(')');
                            try (Statement s = c.createStatement()) {
                                n = s.executeUpdate(sb.toString());   // 처음 보는 조작만 센다
                            }
                        }
                        if (n > 0) {
                            try (Statement s = c.createStatement()) {
                                s.executeUpdate("UPDATE stock_row SET quantity = quantity - " + n + " WHERE product_id = 1");
                            }
                        }
                        c.commit();
                        appliedBatches.incrementAndGet();
                    } catch (SQLException e) {
                        c.rollback();
                        throw e;
                    } finally {
                        c.setAutoCommit(true);
                    }
                }
            }
        }
    }

    /** 대조: Redis 재고 + 미반영 조작 수 = DB 재고. 일정 간격으로 표본을 뜬다. */
    static final class Reconciler {
        final long periodMs;
        final AtomicInteger samples = new AtomicInteger();
        final AtomicInteger mismatches = new AtomicInteger();
        final AtomicInteger alarmsSingle = new AtomicInteger();
        final AtomicInteger alarms3x = new AtomicInteger();
        final List<Long> singleAlarmTimes = new ArrayList<>();
        final List<Long> tripleAlarmTimes = new ArrayList<>();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private Thread thread;

        Reconciler(long periodMs) {
            this.periodMs = periodMs;
        }

        void start() {
            thread = new Thread(() -> {
                int streak = 0;
                while (running.get()) {
                    try {
                        List<Object> snap = snapshot();
                        int redisStock = ((Number) snap.get(0)).intValue();
                        int pending = ((Number) snap.get(1)).intValue() + ((Number) snap.get(2)).intValue();
                        int db = dbRowStock();
                        samples.incrementAndGet();
                        long now = System.nanoTime();
                        if (redisStock + pending != db) {
                            mismatches.incrementAndGet();
                            alarmsSingle.incrementAndGet();
                            synchronized (singleAlarmTimes) {
                                singleAlarmTimes.add(now);
                            }
                            if (++streak == 3) {
                                alarms3x.incrementAndGet();
                                synchronized (tripleAlarmTimes) {
                                    tripleAlarmTimes.add(now);
                                }
                            }
                        } else {
                            streak = 0;
                        }
                        Thread.sleep(periodMs);
                    } catch (Exception e) {
                        return;
                    }
                }
            }, "hotstock-recon");
            thread.setDaemon(true);
            thread.start();
        }

        void stop() throws InterruptedException {
            running.set(false);
            thread.join(5_000);
        }

        long firstAlarmSingleAfter(long t) {
            synchronized (singleAlarmTimes) {
                return singleAlarmTimes.stream().filter(x -> x >= t).findFirst().map(x -> x - t).orElse(-1L);
            }
        }

        long firstAlarm3xAfter(long t) {
            synchronized (tripleAlarmTimes) {
                return tripleAlarmTimes.stream().filter(x -> x >= t).findFirst().map(x -> x - t).orElse(-1L);
            }
        }
    }

    /** BATCH: 커밋 스레드 하나가 창마다 묶어서 한 트랜잭션으로 차감 + 쓰기. */
    static final class BatchSeller extends Seller {
        private record Req(int worker, String ref, CompletableFuture<Outcome> result) {
        }

        private final LinkedBlockingQueue<Req> queue = new LinkedBlockingQueue<>();
        private final long windowMs;
        private final boolean savepoints;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Thread committer;
        final AtomicInteger batches = new AtomicInteger();
        final AtomicInteger items = new AtomicInteger();

        BatchSeller(int stock, long windowMs, boolean savepoints) throws SQLException {
            resetCommon();
            setRowStock(stock);
            this.windowMs = windowMs;
            this.savepoints = savepoints;
            this.committer = new Thread(this::loop, "hotstock-batch");
            this.committer.setDaemon(true);
            this.committer.start();
        }

        @Override
        Outcome sell(int worker, String orderRef) throws SQLException {
            CompletableFuture<Outcome> f = new CompletableFuture<>();
            queue.add(new Req(worker, orderRef, f));
            try {
                return f.get(30, TimeUnit.SECONDS);
            } catch (java.util.concurrent.ExecutionException e) {
                if (e.getCause() instanceof SQLException se) throw se;
                throw new SQLException(e.getCause());
            } catch (Exception e) {
                throw new SQLException(e);
            }
        }

        private void loop() {
            while (running.get()) {
                try {
                    Req first = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (first == null) continue;
                    List<Req> batch = new ArrayList<>();
                    batch.add(first);
                    long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(windowMs);
                    while (batch.size() < BATCH_MAX) {
                        long left = until - System.nanoTime();
                        if (left <= 0) break;
                        Req r = queue.poll(left, TimeUnit.NANOSECONDS);
                        if (r == null) break;
                        batch.add(r);
                    }
                    commit(batch);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        private void commit(List<Req> batch) {
            batches.incrementAndGet();
            items.addAndGet(batch.size());
            try (Connection c = ds.getConnection()) {
                c.setAutoCommit(false);
                try {
                    int q;
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT quantity FROM stock_row WHERE product_id = 1 FOR UPDATE")) {
                        rs.next();
                        q = rs.getInt(1);
                    }
                    List<Req> granted = new ArrayList<>();
                    List<Req> failed = new ArrayList<>();
                    List<SQLException> errors = new ArrayList<>();
                    for (Req r : batch) {
                        if (granted.size() >= q) break;
                        if (savepoints) {
                            var sp = c.setSavepoint();
                            try {
                                followingWrites(c, r.worker, r.ref);
                                granted.add(r);
                            } catch (SQLException e) {
                                c.rollback(sp);
                                failed.add(r);
                                errors.add(e);
                            }
                        } else {
                            followingWrites(c, r.worker, r.ref);   // 한 건이 실패하면 묶음 전체가 되돌아간다
                            granted.add(r);
                        }
                    }
                    try (Statement s = c.createStatement()) {
                        s.executeUpdate("UPDATE stock_row SET quantity = quantity - " + granted.size() + " WHERE product_id = 1");
                    }
                    c.commit();
                    for (Req r : batch) {
                        if (granted.contains(r)) r.result.complete(Outcome.SOLD);
                        else if (failed.contains(r)) r.result.completeExceptionally(errors.get(failed.indexOf(r)));
                        else r.result.complete(Outcome.SOLD_OUT);
                    }
                } catch (SQLException e) {
                    c.rollback();
                    for (Req r : batch) r.result.completeExceptionally(e);
                } finally {
                    c.setAutoCommit(true);
                }
            } catch (SQLException e) {
                for (Req r : batch) r.result.completeExceptionally(e);
            }
        }

        @Override
        int remainingTruthSource() throws SQLException {
            return dbRowStock();
        }

        @Override
        String extra() {
            return "batches=" + batches.get() + ",avg_batch=" + String.format("%.1f", items.get() / (double) Math.max(1, batches.get()));
        }

        @Override
        public void close() throws Exception {
            running.set(false);
            committer.join(5_000);
        }
    }

    // =====================================================================================================
    // 공통
    // =====================================================================================================

    private record Cell(double tps, double p50, double p99, int errors) {
    }

    /** 동시성 conc 로 매진까지 판다. 매 판매 뒤 hook 을 불러 장애를 넣는다. hook 이 0 이상을 돌려주면 그 값을 돌려준다. */
    private static int runUntilSoldOut(Seller seller, int conc, AtomicInteger seq, java.util.function.IntSupplier hook)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(conc);
        AtomicInteger hooked = new AtomicInteger(-1);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < conc; t++) {
            int worker = t;
            fs.add(pool.submit(() -> {
                go.await();
                while (seller.sell(worker, "RS-" + seq.incrementAndGet()) == Outcome.SOLD) {
                    int h = hook.getAsInt();
                    if (h >= 0) hooked.set(h);
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get(300, TimeUnit.SECONDS);
        pool.shutdown();
        return hooked.get();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> snapshot() {
        return (List<Object>) redis.execute(SNAPSHOT, List.of(K_STOCK, K_OPS, K_PROCESSING));
    }

    private static boolean deductRow(Connection c, int n) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE stock_row SET quantity = quantity - ? WHERE product_id = 1 AND quantity >= ?")) {
            ps.setInt(1, n);
            ps.setInt(2, n);
            return ps.executeUpdate() == 1;
        }
    }

    /** 결제 확정에서 재고 차감 뒤 오는 쓰기 셋. 포인트 적립 행이 팔린 주문 기록이다. */
    private static void followingWrites(Connection c, int worker, String orderRef) throws SQLException {
        try (PreparedStatement order = c.prepareStatement("UPDATE orders_sim SET status = 'PAID', updated_at = NOW(6) WHERE id = ?");
             PreparedStatement point = c.prepareStatement("INSERT INTO point_ledger_sim (user_id, amount, order_ref) VALUES (?, 100, ?)");
             PreparedStatement event = c.prepareStatement("INSERT INTO event_pub_sim (listener, payload, published_at) VALUES (?, ?, NOW(6))")) {
            order.setLong(1, worker % 2_000);
            order.executeUpdate();
            point.setLong(1, worker);
            point.setString(2, orderRef);   // 40자를 넘는 주문 번호는 여기서 실패한다(묶음 실패 실험)
            point.executeUpdate();
            event.setString(1, "order.PaymentCompletedListener");
            event.setString(2, PAYLOAD);
            event.executeUpdate();
        }
    }

    private static void resetCommon() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("TRUNCATE point_ledger_sim");
            s.execute("TRUNCATE event_pub_sim");
            s.execute("TRUNCATE stock_op_applied");
        }
    }

    private static void setRowStock(int stock) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM stock_row");
            s.execute("INSERT INTO stock_row VALUES (1, " + stock + ")");
        }
    }

    private static int dbRowStock() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT quantity FROM stock_row WHERE product_id = 1")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static int countSold() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM point_ledger_sim")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static double pct(long[] sorted, int p) {
        if (sorted.length == 0) return Double.NaN;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(p / 100.0 * sorted.length) - 1)] / 1e6;
    }
}
