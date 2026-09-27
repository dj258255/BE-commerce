package com.beomsu.becommerce.order;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 초과 판매 뒤 사후 정정(26절③, AWS DynamoDB 카운터 패턴) 대 조건부 UPDATE — <b>실 MySQL·Redis</b>에서 잰다.
 *
 * <p>{@link StockLockComparisonMySqlTest}와 같은 방법론(스레드 동시 출발, 중앙값 채택)을 쓴다. 다른 것은
 * 비교 대상이다: 조건부 UPDATE(락 없이 원자적이지만 재고 조건을 <b>쓰기 시점에</b> 건다)가 아니라
 * <b>느슨한 카운터</b>(Redis {@code DECR} — 조건 없이 무조건 원자적 감소, MySQL 행 락·트랜잭션 커밋·
 * fsync가 없다) + <b>사후 정정</b>(초과 판매가 난 만큼 가장 늦게 들어온 예약부터 취소)이다.
 *
 * <p><b>느슨한 카운터의 "성공"은 즉시 확정이 아니다.</b> {@code deductLoose}는 재고를 확인하지 않고
 * 항상 예약을 만든다 — 그래서 스레드 150 · 재고 20이면 150건 전부가 일단 예약되고(정정 전 초과 판매
 * 130건), 정정 단계가 가장 늦게 들어온 순(score=나노초 타임스탬프, 늦은 것부터 {@code ZREVRANGE})으로
 * 130건을 취소해 재고 0·초과 판매 0에 수렴시킨다. 이 취소 건수가 이 패턴이 지불하는 대가다.
 *
 * <p>기본 스위트에서는 제외한다(Docker 필요). {@code ./gradlew integrationTest}로 실행.
 */
@Tag("integration")
class StockLooseCounterReconciliationTest {

    private static final int STOCK = 20;
    private static final int THREADS = 150;      // 기존 테스트의 HIGH_THREADS와 동일 — 고경합
    private static final int POOL = 170;
    private static final int ROUNDS = 5;
    private static final int WARMUP = 2;

    private static String jdbcUrl;
    private static HikariDataSource ds;
    private static final GenericContainer<?> REDIS = SharedContainers.redis();
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void startDb() throws SQLException {
        jdbcUrl = SharedContainers.freshDatabase("loosecounter");

        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(jdbcUrl);
        cfg.setUsername(SharedContainers.username());
        cfg.setPassword(SharedContainers.password());
        cfg.setMaximumPoolSize(POOL);
        cfg.setMinimumIdle(POOL);
        ds = new HikariDataSource(cfg);

        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE stock (id BIGINT PRIMARY KEY, quantity INT NOT NULL)");
        }

        SharedContainers.flushRedis();
        redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(redisFactory);
        redis.afterPropertiesSet();

        System.out.printf("%n=== 초과 판매 사후 정정 비교 · 재고 %d · 풀 %d ===%n", STOCK, POOL);
    }

    @AfterAll
    static void stopDb() {
        if (ds != null) ds.close();
        if (redisFactory != null) redisFactory.destroy();
    }

    @Test
    @DisplayName("조건부 UPDATE(MySQL, 고경합 150스레드): 항상 초과 판매 0, 정확히 완판")
    void conditionalHighContention() throws Exception {
        ConditionalResult r = measureConditional(THREADS);
        assertThat(r.oversold).isZero();
        assertThat(r.finalQuantity).isZero();
        assertThat(r.success).isEqualTo(STOCK);
        System.out.printf("[조건부 UPDATE] 스레드=%d 중앙값=%dms 성공=%d/%d 초과판매=0%n",
                THREADS, r.medianMs, r.success, STOCK);
    }

    @Test
    @DisplayName("느슨한 카운터+사후 정정(Redis, 고경합 150스레드): 정정 전엔 초과 판매, 정정 후엔 0")
    void looseCounterHighContentionThenReconcile() throws Exception {
        LooseResult r = measureLoose(THREADS);

        // 정정 전 — 이 패턴의 정의대로 무조건 예약되므로 초과 판매가 난다.
        assertThat(r.admitted).isEqualTo(THREADS);              // 전부 "성공"(무조건 예약)
        assertThat(r.oversoldBeforeReconcile).isEqualTo(THREADS - STOCK);
        assertThat(r.oversoldBeforeReconcile).isPositive();      // 이 조건에서는 반드시 초과 판매가 난다(증거)

        // 사후 정정 — 늦게 들어온 순으로 초과분만큼 취소.
        int cancelled = reconcile(r.stockKey, r.logKey, r.oversoldBeforeReconcile);
        int finalCounter = readCounter(r.stockKey);

        assertThat(cancelled).isEqualTo(r.oversoldBeforeReconcile);   // 취소 건수 == 정정 전 초과 판매(증거)
        assertThat(finalCounter).isZero();                            // 정정 후 재고 정확히 0
        assertThat(Math.max(0, -finalCounter)).isZero();               // 정정 후 초과 판매 0
        assertThat(remainingLogSize(r.logKey)).isEqualTo(STOCK);       // 살아남은 예약 수 == 재고

        System.out.printf("[느슨한 카운터] 스레드=%d 중앙값(burst)=%dms 예약=%d 정정전초과판매=%d 취소=%d 정정후초과판매=0%n",
                THREADS, r.medianMs, r.admitted, r.oversoldBeforeReconcile, cancelled);
    }

    @Test
    @DisplayName("처리량 비교(150스레드): 느슨한 카운터(burst)와 조건부 UPDATE 중앙값을 나란히 찍는다")
    void looseCounterThroughputComparison() throws Exception {
        // 상대 속도는 이 테스트가 강제하는 불변식이 아니라 이슈 #412에서 측정 전에 적은 가설이다 —
        // CI 환경(공유 러너)의 잡음에 흔들리지 않게 여기서는 값만 남기고, 판정은 PR·ADR에서 한다.
        ConditionalResult conditional = measureConditional(THREADS);
        LooseResult loose = measureLoose(THREADS);
        // 정정 단계는 처리량 비교에 넣지 않는다 — 실제로는 나중에 배치로 비동기로 도는 단계다.
        reconcile(loose.stockKey, loose.logKey, loose.oversoldBeforeReconcile);

        System.out.printf("[처리량 비교] 조건부 UPDATE 중앙값=%dms · 느슨한 카운터(burst) 중앙값=%dms · 차이=%dms(%.1f%%)%n",
                conditional.medianMs, loose.medianMs, conditional.medianMs - loose.medianMs,
                100.0 * (conditional.medianMs - loose.medianMs) / conditional.medianMs);

        assertThat(conditional.medianMs).isGreaterThan(0);
        assertThat(loose.medianMs).isGreaterThan(0);
    }

    // --- 조건부 UPDATE(MySQL) — StockLockComparisonMySqlTest와 같은 SQL ---

    private record ConditionalResult(long medianMs, int success, int oversold, int finalQuantity) {
    }

    private ConditionalResult measureConditional(int threads) throws Exception {
        long[] took = new long[ROUNDS];
        int lastSuccess = 0;
        int lastFinalQty = 0;
        for (int i = 0; i < WARMUP + ROUNDS; i++) {
            resetStock();
            AtomicInteger success = new AtomicInteger();
            long ms = runBurst(threads, () -> {
                try (Connection c = ds.getConnection();
                     PreparedStatement ps = c.prepareStatement(
                             "UPDATE stock SET quantity = quantity - 1 WHERE id = 1 AND quantity >= 1")) {
                    if (ps.executeUpdate() == 1) {
                        success.incrementAndGet();
                    }
                }
            });
            if (i >= WARMUP) {
                took[i - WARMUP] = ms;
            }
            lastSuccess = success.get();
            lastFinalQty = currentQuantity();
        }
        java.util.Arrays.sort(took);
        int oversold = Math.max(0, lastSuccess - STOCK);
        return new ConditionalResult(took[ROUNDS / 2], lastSuccess, oversold, lastFinalQty);
    }

    // --- 느슨한 카운터(Redis DECR, 조건 없음) ---

    private record LooseResult(long medianMs, int admitted, int oversoldBeforeReconcile,
                               String stockKey, String logKey) {
    }

    private LooseResult measureLoose(int threads) throws Exception {
        long[] took = new long[ROUNDS];
        String stockKey = null;
        String logKey = null;
        int lastAdmitted = 0;
        int lastOversold = 0;
        for (int i = 0; i < WARMUP + ROUNDS; i++) {
            String runId = UUID.randomUUID().toString();
            stockKey = "loose:stock:" + runId;
            logKey = "loose:log:" + runId;
            redis.opsForValue().set(stockKey, String.valueOf(STOCK));
            String finalLogKey = logKey;
            String finalStockKey = stockKey;
            long ms = runBurst(threads, () -> {
                // 무조건 원자적 감소 — 재고 확인 없음(이 패턴의 정의). 곧이어 언제 예약됐는지 기록한다.
                redis.opsForValue().decrement(finalStockKey);
                redis.opsForZSet().add(finalLogKey, UUID.randomUUID().toString(), System.nanoTime());
            });
            if (i >= WARMUP) {
                took[i - WARMUP] = ms;
            }
            lastAdmitted = remainingLogSize(finalLogKey);
            lastOversold = Math.max(0, -readCounter(finalStockKey));
            if (i < WARMUP + ROUNDS - 1) {
                // 마지막 라운드만 다음 테스트(정정 검증)에 남긴다. 그 전 라운드는 여기서 청소한다.
                redis.delete(java.util.List.of(finalStockKey, finalLogKey));
            }
        }
        java.util.Arrays.sort(took);
        return new LooseResult(took[ROUNDS / 2], lastAdmitted, lastOversold, stockKey, logKey);
    }

    /** 늦게 들어온 순(score 내림차순)으로 초과분만큼 취소 — 재고를 되돌리고 로그에서 지운다. 취소 건수를 돌려준다. */
    private int reconcile(String stockKey, String logKey, int oversold) {
        if (oversold <= 0) {
            return 0;
        }
        Set<String> latest = redis.opsForZSet().reverseRange(logKey, 0, oversold - 1);
        int cancelled = latest == null ? 0 : latest.size();
        if (cancelled > 0) {
            redis.opsForZSet().remove(logKey, latest.toArray());
            redis.opsForValue().increment(stockKey, cancelled);
        }
        return cancelled;
    }

    private int readCounter(String key) {
        String v = redis.opsForValue().get(key);
        return v == null ? 0 : Integer.parseInt(v);
    }

    private int remainingLogSize(String logKey) {
        Long size = redis.opsForZSet().zCard(logKey);
        return size == null ? 0 : size.intValue();
    }

    // --- 공통: 동시 출발 burst 실행 ---

    @FunctionalInterface
    private interface Attempt {
        void run() throws Exception;
    }

    /** 스레드를 동시에 출발시키고 전부 끝날 때까지 걸린 시간(ms)을 돌려준다. */
    private long runBurst(int threads, Attempt attempt) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads + 2);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    attempt.run();
                } catch (Exception e) {
                    // 측정 목적 — 개별 실패는 성공 카운터에 안 잡히는 것으로 충분하다.
                }
            });
        }
        ready.await();
        long began = System.nanoTime();
        start.countDown();
        pool.shutdown();
        boolean done = pool.awaitTermination(60, TimeUnit.SECONDS);
        long tookMs = (System.nanoTime() - began) / 1_000_000;
        assertThat(done).isTrue();
        return tookMs;
    }

    private void resetStock() throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.execute("DELETE FROM stock");
            s.execute("INSERT INTO stock VALUES (1, " + STOCK + ")");
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
