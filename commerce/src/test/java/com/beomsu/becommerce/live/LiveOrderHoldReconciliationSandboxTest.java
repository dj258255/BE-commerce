package com.beomsu.becommerce.live;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * R13·R14(샌드박스 실 MySQL·Redis) — {@link LiveOrderConcurrencySandboxTest}와 같은 이유로
 * Docker 없이 이 b-studio 샌드박스의 실 애드온에 평범한 {@code @SpringBootTest}로 붙는다. R13의
 * "5분" TTL을 실제로 기다리지 않도록 {@code app.live.order.hold-ttl}을 짧게 오버라이드한다
 * (클래스 수준 {@code @SpringBootTest(properties=...)}라 다른 샌드박스 테스트와 클래스를
 * 나눴다 — 같은 클래스 안에서는 프로퍼티를 테스트별로 바꿀 수 없다).
 *
 * <p>{@code LivePinBroadcaster}는 {@code @MockBean}으로 바꾸지 않는다 — 그 인터페이스를
 * 구현하는 {@code LivePinWebSocketHandler}를 {@code LivePinWebSocketConfig}가 콘크리트
 * 타입으로 직접 의존해서, 인터페이스를 모킹하면 그 빈이 통째로 사라져 컨텍스트가 안 뜬다.
 * 대신 {@link LiveOrderService}·{@link LiveOrderHoldReconciler}를 이 테스트에서 직접
 * 생성해(다른 의존은 전부 실제 스프링 빈을 그대로 주입) 방송 호출만 가짜로 본다 — 둘 다
 * public 생성자가 있어 가능하다.
 */
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:mysql://mysql:.*")
@SpringBootTest(properties = "app.live.order.hold-ttl=100ms")
@DisplayName("R13·R14(샌드박스 실 MySQL·Redis): 미결제 반환·UNKNOWN 유지·매진 즉시 방송")
class LiveOrderHoldReconciliationSandboxTest {

    @Autowired
    LivePinRepository pinRepository;
    @Autowired
    OrderPlacement orderPlacement;
    @Autowired
    LiveOrderGate gate;
    @Autowired
    OrderPaymentStatus orderPaymentStatus;
    @Autowired
    JdbcTemplate jdbc;

    private LivePinBroadcaster broadcaster;
    private LiveOrderService liveOrderService;
    private LiveOrderHoldReconciler reconciler;

    private final List<Long> broadcastIds = new ArrayList<>();
    private final List<Long> productIds = new ArrayList<>();
    private final List<long[]> userIdRanges = new ArrayList<>();
    private final List<String> idempotencyKeyPrefixes = new ArrayList<>();

    @BeforeEach
    void wireWithFakeBroadcaster() {
        broadcaster = mock(LivePinBroadcaster.class);
        liveOrderService = new LiveOrderService(pinRepository, orderPlacement, gate, broadcaster, new LivePinCache());
        reconciler = new LiveOrderHoldReconciler(pinRepository, gate, orderPaymentStatus, broadcaster);
    }

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
        }
        for (long productId : productIds) {
            jdbc.update("DELETE FROM stock WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM products WHERE product_id = ?", productId);
        }
    }

    private long newProduct() {
        long id = 94_185_000_000L + (long) (Math.random() * 1_000_000_000L);
        jdbc.update("INSERT INTO products (product_id, name, price) VALUES (?, ?, 10000)", id, "R13 샌드박스 " + id);
        jdbc.update("INSERT INTO stock (product_id, quantity, version) VALUES (?, ?, 0)", id, 10_000);
        productIds.add(id);
        return id;
    }

    private long newBroadcastWithPin(long productId, int limit) {
        long broadcastId = 96_185_000_000L + (long) (Math.random() * 1_000_000_000L);
        broadcastIds.add(broadcastId);
        LivePin pin = LivePin.forBroadcast(broadcastId, Instant.now());
        pin.pin(productId, 9_900L, limit, Instant.now());
        pinRepository.save(pin);
        return broadcastId;
    }

    private OrderPlacement.PlacedOrder placeOrder(long broadcastId, long productId, long userId, String idemKey) {
        userIdRanges.add(new long[] {userId, 1});
        idempotencyKeyPrefixes.add(idemKey);
        return liveOrderService.order(userId, broadcastId, productId, idemKey);
    }

    private void setOrderStatus(String orderNo, String status) {
        jdbc.update("UPDATE orders SET status = ? WHERE order_no = ?", status, orderNo);
    }

    @Test
    @DisplayName("R13.1: 선점 후 TTL(여기서는 300ms로 짧게)이 지난 미결제(PENDING_PAYMENT) 주문의 "
            + "선점은 반환되고 남은 수량이 복구된다")
    void releasesExpiredUnpaidHold() throws InterruptedException {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);
        placeOrder(broadcastId, productId, 9_100_000_001L, "sandbox-r131-" + System.nanoTime());
        long generation = pinRepository.findByBroadcastId(broadcastId).orElseThrow().getGeneration();
        assertThat(gate.currentCount(broadcastId, generation)).isEqualTo(1);

        Thread.sleep(1000);   // TTL(100ms)을 넉넉히 넘긴다 — 5분을 실제로 기다리지 않는다
        int released = reconciler.reconcileAll();

        assertThat(released).isGreaterThanOrEqualTo(1);
        assertThat(gate.currentCount(broadcastId, generation)).isZero();   // 남은 수량 복구(3으로)
    }

    @Test
    @DisplayName("R13.2: 결제 결과가 UNKNOWN(PAYMENT_IN_PROGRESS)인 주문은 TTL이 지나도 선점이 유지된다")
    void keepsHoldWhenPaymentResultIsUnknown() throws InterruptedException {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);
        OrderPlacement.PlacedOrder placed =
                placeOrder(broadcastId, productId, 9_100_000_101L, "sandbox-r132-" + System.nanoTime());
        setOrderStatus(placed.orderNo(), "PAYMENT_IN_PROGRESS");   // 기존 체크아웃 사가의 UNKNOWN 표현(ADR-007)
        long generation = pinRepository.findByBroadcastId(broadcastId).orElseThrow().getGeneration();

        Thread.sleep(1000);
        reconciler.reconcileAll();

        assertThat(gate.currentCount(broadcastId, generation)).isEqualTo(1);   // 반환되지 않았다
    }

    @Test
    @DisplayName("R13: 결제가 확정(PAID)된 주문은 TTL이 지나도 영구히 선점을 유지하고, "
            + "다시 평가해도(두 번째 reconcile) 그대로다")
    void permanentlyConfirmsPaidHold() throws InterruptedException {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);
        OrderPlacement.PlacedOrder placed =
                placeOrder(broadcastId, productId, 9_100_000_201L, "sandbox-r13paid-" + System.nanoTime());
        setOrderStatus(placed.orderNo(), "PAID");
        long generation = pinRepository.findByBroadcastId(broadcastId).orElseThrow().getGeneration();

        Thread.sleep(1000);
        reconciler.reconcileAll();
        assertThat(gate.currentCount(broadcastId, generation)).isEqualTo(1);

        Thread.sleep(1000);
        reconciler.reconcileAll();   // 다시 평가해도 영구화됐으므로 그대로다
        assertThat(gate.currentCount(broadcastId, generation)).isEqualTo(1);
    }

    @Test
    @DisplayName("R14.1: 한정 수량의 마지막 1개가 확정되는 순간, 그 요청을 처리하는 동안(동기) "
            + "매진(남은 수량 0) 이벤트가 방송된다 — 별도 폴링·지연 없이 즉시")
    void broadcastsSoldOutSynchronouslyOnLastUnit() {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);

        placeOrder(broadcastId, productId, 9_100_000_301L, "sandbox-r141-1-" + System.nanoTime());
        placeOrder(broadcastId, productId, 9_100_000_302L, "sandbox-r141-2-" + System.nanoTime());
        placeOrder(broadcastId, productId, 9_100_000_303L, "sandbox-r141-3-" + System.nanoTime());   // 마지막 1개

        ArgumentCaptor<LivePinEventView> captor = ArgumentCaptor.forClass(LivePinEventView.class);
        verify(broadcaster, atLeastOnce()).broadcast(captor.capture());
        List<LivePinEventView> soldOutEvents = captor.getAllValues().stream()
                .filter(e -> e.type() == LivePinEventType.QUANTITY_CHANGED)
                .filter(e -> e.remainingQuantity() != null && e.remainingQuantity() == 0)
                .toList();
        assertThat(soldOutEvents).as("세 번째(마지막) 주문 처리 안에서 매진(0) 방송이 나갔어야 한다")
                .isNotEmpty();
    }
}
