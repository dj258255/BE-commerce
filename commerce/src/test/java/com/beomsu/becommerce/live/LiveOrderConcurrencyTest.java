package com.beomsu.becommerce.live;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R12.1·R12.2: 방송 특가 한정 수량의 실제 동시성 — 실 MySQL·Redis(Testcontainers)로 스레드
 * 여러 개가 동시에 주문해도 확정 주문 수량 합이 정확히 N이고 초과분은 전부 거절됨을, 그리고
 * 같은 멱등 키 재시도가 주문을 두 번 만들지 않음을 확인한다.
 *
 * <p>{@code CheckoutService.createSpecialPriceOrder}(상품 실존·카탈로그 재고)와
 * {@code LiveOrderGate}(Redis 선점, 이 모듈이 더하는 "방송-상품" 한도)를 모두 실제로 태운다 —
 * {@link StockLockComparisonMySqlTest}·{@code ContextStoreConcurrencyTest}와 같은 이유로
 * Docker 가 필요해 기본 {@code test}가 아니라 {@code integrationTest}로 뗀다.
 *
 * <p>실제 스펙 수치(한정 50개·동시 1,000명)는 {@code tools/run-live-order-stock.sh}(k6)로
 * 실 서버에 대고 따로 확인한다 — 여기서는 CI 소요 시간을 고려해 더 작은 배수(한정 20개·스레드
 * 80개, 4배)로 같은 불변식을 증명한다.
 */
@Tag("integration")
@SpringBootTest
@DisplayName("R12: 한정 수량 N에 동시 주문 — 확정 합계는 N을 넘지 않는다")
class LiveOrderConcurrencyTest {

    private static final int LIMIT = 20;
    private static final int THREADS = 80;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "LiveOrderConcurrency");
    }

    @Autowired
    LivePinRepository pinRepository;
    @Autowired
    LiveOrderService liveOrderService;
    @Autowired
    JdbcTemplate jdbc;

    private long product(int stock) {
        long id = 91_085_000L + (System.nanoTime() % 1_000_000);
        jdbc.update("INSERT INTO products (product_id, name, price) VALUES (?, ?, 10000)", id, "방송 특가 실험 " + id);
        jdbc.update("INSERT INTO stock (product_id, quantity, version) VALUES (?, ?, 0)", id, stock);
        return id;
    }

    private void pin(long broadcastId, long productId, long price, int limit) {
        LivePin pin = pinRepository.findByBroadcastId(broadcastId)
                .orElseGet(() -> LivePin.forBroadcast(broadcastId, Instant.now()));
        pin.pin(productId, price, limit, Instant.now());
        pinRepository.save(pin);
    }

    private int confirmedOrders(long broadcastId, long productId, long firstUserId, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long userId = firstUserId + i;
            String idemKey = "idem-repin-" + firstUserId + "-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    liveOrderService.order(userId, broadcastId, productId, idemKey);
                    confirmed.incrementAndGet();
                } catch (LiveOrderException ignored) {
                    // 매진 거절 — 이 테스트는 확정 건수만 본다
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
        return confirmed.get();
    }

    @Test
    @DisplayName("R12.1: 한정 수량 N이 걸린 상품에 스레드 여러 개가 동시에 각 1개씩 주문하면 "
            + "확정 합계가 정확히 N이고 나머지는 전부 거절된다")
    void concurrentOrdersNeverExceedLimit() throws Exception {
        long broadcastId = 1_234_000L + (System.nanoTime() % 1000);
        long productId = product(10_000);   // 카탈로그 재고는 넉넉히 둔다 — 병목은 Redis 게이트여야 한다
        pin(broadcastId, productId, 9_900L, LIMIT);

        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch ready = new CountDownLatch(THREADS);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger confirmed = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        long firstUserId = 7_000_000L;

        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            long userId = firstUserId + i;
            String idemKey = "idem-concurrency-" + i;
            futures.add(pool.submit(() -> {
                ready.countDown();
                start.await();
                try {
                    liveOrderService.order(userId, broadcastId, productId, idemKey);
                    confirmed.incrementAndGet();
                } catch (LiveOrderException e) {
                    assertThat(e.code()).isEqualTo("LIMITED_QUANTITY_SOLD_OUT"); // 거절 사유는 매진 코드여야 한다
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

        assertThat(confirmed.get()).isEqualTo(LIMIT);
        assertThat(rejected.get()).isEqualTo(THREADS - LIMIT);
        Integer orderCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM orders WHERE user_id >= ? AND user_id < ?",
                Integer.class, firstUserId, firstUserId + THREADS);
        assertThat(orderCount).isEqualTo(LIMIT);   // 수량은 1건씩이므로 주문 건수 = 확정 수량 합
    }

    @Test
    @DisplayName("R12.2: 같은 멱등 키로 4번 보내도 주문은 1건만 생성되고 네 응답 모두 같은 주문 id다")
    void sameIdempotencyKeyCreatesOnlyOneOrder() {
        long broadcastId = 2_234_000L + (System.nanoTime() % 1000);
        long productId = product(10_000);
        pin(broadcastId, productId, 9_900L, LIMIT);
        long userId = 7_999_999L;
        String idemKey = "idem-1";

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
    @DisplayName("R12(재고정 결함 재현·수정 확인): 한정 수량을 완판시킨 뒤 같은 방송·같은 상품을 "
            + "새 한정 수량으로 다시 고정하면, 새 드롭은 이전 드롭의 선점에 영향받지 않고 "
            + "새 한도만큼 다시 확정된다(세대 분리, ADR-085)")
    void rePinningStartsFreshLimitUnaffectedByPreviousDrop() throws Exception {
        long broadcastId = 3_234_000L + (System.nanoTime() % 1000);
        long productId = product(10_000);
        int firstLimit = 5;
        int secondLimit = 8;

        pin(broadcastId, productId, 9_900L, firstLimit);
        int firstConfirmed = confirmedOrders(broadcastId, productId, 8_000_000L, firstLimit * 3);
        assertThat(firstConfirmed).isEqualTo(firstLimit);   // 1번째 드롭 완판

        // 재고정 — 수정 전이라면 Redis 키가 broadcastId로만 갈려 있어 이전 완판분이 그대로 남는다.
        pin(broadcastId, productId, 7_900L, secondLimit);
        int secondConfirmed = confirmedOrders(broadcastId, productId, 8_500_000L, secondLimit * 3);

        assertThat(secondConfirmed)
                .as("재고정 결함이 고쳐졌다면 새 드롭은 이전 완판과 무관하게 새 한도(%d)만큼 확정돼야 한다", secondLimit)
                .isEqualTo(secondLimit);
    }
}
