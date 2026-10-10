package com.beomsu.becommerce.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12.1·R12.2(+재고정 수정 확인) — {@link LiveOrderConcurrencyTest}(Testcontainers)와 같은
 * 불변식을, Docker 없이 <b>이 b-studio 샌드박스에 이미 떠 있는 실 MySQL·Redis 애드온</b>으로
 * 확인한다. 그래서 b-studio가 돌리는 기본 {@code test} 게이트에서 실제로 실행된다 —
 * Testcontainers 버전은 이 샌드박스에 Docker가 없어({@code integrationTest}가 "Could not
 * find a valid Docker environment"로 실패) R12의 핵심 보장(확정 합계 ≤ N)을 게이트가 단 한
 * 번도 확인하지 못하고 있었다.
 *
 * <p><b>왜 Testcontainers로 바꾸지 않고 새 클래스를 더했나</b>: 이 샌드박스의 commerce 컨테이너
 * 자체가 Testcontainers(Docker-in-Docker)를 못 쓴다 — {@code LiveOrderConcurrencyTest}를 손대도
 * 못 돈다. 대신 이 컨테이너는 compose 애드온 mysql·redis에 <b>이미</b>({@code
 * SPRING_DATASOURCE_URL=jdbc:mysql://mysql:3306/becommerce} 등, 실행 중인 commerce 앱 자신이
 * 쓰는 것과 같은 호스트) 직접 닿는다 — 그래서 {@link SharedContainers} 없이 평범한
 * {@code @SpringBootTest}로 이 환경 변수가 가리키는 DB·Redis에 그대로 붙는다.
 *
 * <p><b>이 변수가 없는 로컬·CI에서는 건너뛴다</b>({@link EnabledIfEnvironmentVariable}) — 그런
 * 환경은 {@link LiveOrderConcurrencyTest}가 Testcontainers로 계속 맡는다(요구사항: "로컬·CI에서는
 * 기존 Testcontainers 방식도 그대로 돌게 둔다"). 두 테스트는 같은 시나리오를 서로 다른
 * 인프라로 중복 확인하는 관계다 — 하나를 지우면 안 된다.
 *
 * <p><b>테스트 데이터</b>: 이 DB는 실행 중인 commerce 앱과 <b>같은 스키마를 공유</b>한다 —
 * 그래서 테스트가 만든 행은 {@link #cleanUp()}에서 전부 지운다(요구사항: "테스트 데이터는
 * 테스트가 만들고 지운다"). productId·broadcastId·userId는 다른 테스트·실 데이터와 겹치지
 * 않게 높은 대역(9_1xx_xxx 이상)에서 난수로 고른다.
 */
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:mysql://mysql:.*")
@SpringBootTest
@DisplayName("R12(샌드박스 실 MySQL·Redis): 한정 수량 N에 동시 주문 — 확정 합계는 N을 넘지 않는다")
class LiveOrderConcurrencySandboxTest {

    private static final int LIMIT = 10;
    private static final int THREADS = 40;

    @Autowired
    LivePinRepository pinRepository;
    @Autowired
    LiveOrderService liveOrderService;
    @Autowired
    LivePinCache pinCache;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;

    private final List<Long> broadcastIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<String> idempotencyKeyPrefixes = new ArrayList<>();
    private final List<long[]> userIdRanges = new ArrayList<>();   // {firstUserId, count}

    @AfterEach
    void cleanUp() {
        for (long[] range : userIdRanges) {
            long first = range[0];
            long count = range[1];
            jdbc.update("DELETE FROM order_items WHERE order_id IN "
                    + "(SELECT id FROM orders WHERE user_id >= ? AND user_id < ?)", first, first + count);
            jdbc.update("DELETE FROM orders WHERE user_id >= ? AND user_id < ?", first, first + count);
        }
        for (String prefix : idempotencyKeyPrefixes) {
            jdbc.update("DELETE FROM idempotency_keys WHERE idempotency_key LIKE ?", prefix + "%");
        }
        for (long broadcastId : broadcastIds) {
            jdbc.update("DELETE FROM live_pins WHERE broadcast_id = ?", broadcastId);
            Set<String> keys = redis.keys("live:pin:" + broadcastId + ":*");
            if (keys != null && !keys.isEmpty()) {
                redis.delete(keys);
            }
        }
        for (long productId : productIds) {
            jdbc.update("DELETE FROM stock WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM products WHERE product_id = ?", productId);
        }
    }

    private long newProduct(int stock) {
        long id = 93_185_000_000L + (long) (Math.random() * 1_000_000_000L);
        jdbc.update("INSERT INTO products (product_id, name, price) VALUES (?, ?, 10000)", id, "샌드박스 실험 " + id);
        jdbc.update("INSERT INTO stock (product_id, quantity, version) VALUES (?, ?, 0)", id, stock);
        productIds.add(id);
        return id;
    }

    private long newBroadcastId() {
        long id = 95_185_000_000L + (long) (Math.random() * 1_000_000_000L);
        broadcastIds.add(id);
        return id;
    }

    private void pin(long broadcastId, long productId, long price, int limit) {
        LivePin pin = pinRepository.findByBroadcastId(broadcastId)
                .orElseGet(() -> LivePin.forBroadcast(broadcastId, Instant.now()));
        pin.pin(productId, price, limit, Instant.now());
        pinRepository.save(pin);
        // R15: LivePinService를 거치지 않고 리포지토리를 직접 쓰므로, 운영 코드가 하는 캐시
        // 갱신도 여기서 해 줘야 재고정(세대 증가) 테스트가 낡은 세대를 보지 않는다.
        pinCache.put(pin);
    }

    /** 스레드 여러 개가 서로 다른 사용자·멱등 키로 동시에 1건씩 주문한다. 확정 건수를 돌려준다. */
    private int concurrentOrders(long broadcastId, long productId, long firstUserId, int threads, String idemPrefix)
            throws Exception {
        userIdRanges.add(new long[] {firstUserId, threads});
        idempotencyKeyPrefixes.add(idemPrefix);

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long userId = firstUserId + i;
            String idemKey = idemPrefix + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    liveOrderService.order(userId, broadcastId, productId, idemKey);
                    confirmed.incrementAndGet();
                } catch (LiveOrderException e) {
                    assertThat(e.code()).isEqualTo("LIMITED_QUANTITY_SOLD_OUT");
                    rejected.incrementAndGet();
                }
                return null;
            }));
        }
        ready.await();
        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertThat(confirmed.get() + rejected.get()).isEqualTo(threads);
        return confirmed.get();
    }

    @Test
    @DisplayName("R12.1: 한정 수량 N이 걸린 상품에 스레드 여러 개가 동시에 각 1개씩 주문하면 "
            + "확정 합계가 정확히 N이고 나머지는 전부 거절된다(실 MySQL·Redis)")
    void concurrentOrdersNeverExceedLimit() throws Exception {
        long broadcastId = newBroadcastId();
        long productId = newProduct(10_000);
        pin(broadcastId, productId, 9_900L, LIMIT);

        int confirmed = concurrentOrders(broadcastId, productId, 8_100_000_000L, THREADS, "sandbox-r121-");

        assertThat(confirmed).isEqualTo(LIMIT);
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id >= ? AND user_id < ?",
                Integer.class, 8_100_000_000L, 8_100_000_000L + THREADS);
        assertThat(orderCount).isEqualTo(LIMIT);   // 확정 주문 수량 합(실 DB에 실제로 쌓인 행) = N
    }

    @Test
    @DisplayName("R12.2: 같은 멱등 키로 4번 보내도 주문은 1건만 생성되고 네 응답 모두 같은 주문 id다(실 MySQL·Redis)")
    void sameIdempotencyKeyCreatesOnlyOneOrder() {
        long broadcastId = newBroadcastId();
        long productId = newProduct(10_000);
        pin(broadcastId, productId, 9_900L, LIMIT);
        long userId = 8_200_000_001L;
        String idemKey = "sandbox-r122-fixed";
        userIdRanges.add(new long[] {userId, 1});
        idempotencyKeyPrefixes.add(idemKey);

        String orderNo1 = liveOrderService.order(userId, broadcastId, productId, idemKey).orderNo();
        String orderNo2 = liveOrderService.order(userId, broadcastId, productId, idemKey).orderNo();
        String orderNo3 = liveOrderService.order(userId, broadcastId, productId, idemKey).orderNo();
        String orderNo4 = liveOrderService.order(userId, broadcastId, productId, idemKey).orderNo();

        assertThat(List.of(orderNo1, orderNo2, orderNo3, orderNo4)).containsOnly(orderNo1);
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id = ?", Integer.class, userId);
        assertThat(orderCount).isEqualTo(1);
    }

    @Test
    @DisplayName("R12(재고정 결함 재현·수정 확인): 완판 뒤 같은 방송·같은 상품을 새 한정 수량으로 "
            + "다시 고정하면, 새 드롭은 이전 드롭의 선점과 무관하게 새 한도만큼 다시 확정된다(실 MySQL·Redis)")
    void rePinningStartsFreshLimitUnaffectedByPreviousDrop() throws Exception {
        long broadcastId = newBroadcastId();
        long productId = newProduct(10_000);
        int firstLimit = 4;
        int secondLimit = 6;

        pin(broadcastId, productId, 9_900L, firstLimit);
        int firstConfirmed = concurrentOrders(broadcastId, productId, 8_300_000_000L, firstLimit * 3,
                "sandbox-repin-1-");
        assertThat(firstConfirmed).isEqualTo(firstLimit);   // 1번째 드롭 완판

        pin(broadcastId, productId, 7_900L, secondLimit);   // 재고정 — 세대가 올라간다(ADR-085)
        int secondConfirmed = concurrentOrders(broadcastId, productId, 8_400_000_000L, secondLimit * 3,
                "sandbox-repin-2-");

        assertThat(secondConfirmed)
                .as("재고정 결함이 고쳐졌다면 새 드롭은 이전 완판과 무관하게 새 한도(%d)만큼 확정돼야 한다", secondLimit)
                .isEqualTo(secondLimit);
    }
}
