package com.beomsu.becommerce.live;

import com.beomsu.becommerce.testsupport.SharedContainers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * R13·R14(Testcontainers MySQL·Redis, 로컬·CI용) —
 * {@link LiveOrderHoldReconciliationSandboxTest}와 같은 시나리오를 Docker 기반 인프라로
 * 다시 확인한다(둘 다 유지, {@link LiveOrderConcurrencyTest}·{@code
 * LiveOrderConcurrencySandboxTest}와 같은 관계). Docker가 필요해 기본 {@code test}가 아니라
 * {@code integrationTest}로 뗀다. 실 배경 스케줄러(기본 5초 주기 {@code
 * LiveOrderHoldRecoveryScheduler})와 이 테스트의 수동 {@code reconcileAll()} 호출이 경합하지
 * 않도록 그 주기도 테스트 수명보다 길게 늘려 둔다.
 */
@Tag("integration")
@SpringBootTest(properties = {
        "app.live.order.hold-ttl=100ms",
        "app.live.order.hold-recovery.interval-ms=600000"
})
@DisplayName("R13·R14(Testcontainers): 미결제 반환·UNKNOWN 유지·매진 즉시 방송")
class LiveOrderHoldReconciliationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "LiveOrderHoldReconciliation");
    }

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

    @BeforeEach
    void wireWithFakeBroadcaster() {
        broadcaster = mock(LivePinBroadcaster.class);
        liveOrderService = new LiveOrderService(pinRepository, orderPlacement, gate, broadcaster, new LivePinCache());
        reconciler = new LiveOrderHoldReconciler(pinRepository, gate, orderPaymentStatus, broadcaster);
    }

    private final List<Long> productIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (long productId : productIds) {
            jdbc.update("DELETE FROM stock WHERE product_id = ?", productId);
            jdbc.update("DELETE FROM products WHERE product_id = ?", productId);
        }
    }

    private long newProduct() {
        long id = 95_085_000L + (long) (Math.random() * 1_000_000L);
        jdbc.update("INSERT INTO products (product_id, name, price) VALUES (?, ?, 10000)", id, "R13 실험 " + id);
        jdbc.update("INSERT INTO stock (product_id, quantity, version) VALUES (?, ?, 0)", id, 10_000);
        productIds.add(id);
        return id;
    }

    private long newBroadcastWithPin(long productId, int limit) {
        long broadcastId = 97_085_000L + (long) (Math.random() * 1_000_000L);
        LivePin pin = LivePin.forBroadcast(broadcastId, Instant.now());
        pin.pin(productId, 9_900L, limit, Instant.now());
        pinRepository.save(pin);
        return broadcastId;
    }

    private void setOrderStatus(String orderNo, String status) {
        jdbc.update("UPDATE orders SET status = ? WHERE order_no = ?", status, orderNo);
    }

    @Test
    @DisplayName("R13.1: TTL이 지난 미결제(PENDING_PAYMENT) 주문의 선점은 반환되고 남은 수량이 복구된다")
    void releasesExpiredUnpaidHold() throws InterruptedException {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);
        long generation = pinRepository.findByBroadcastId(broadcastId).orElseThrow().getGeneration();
        liveOrderService.order(1L, broadcastId, productId, "it-r131-" + System.nanoTime());

        Thread.sleep(1000);
        int released = reconciler.reconcileAll();

        assertThat(released).isGreaterThanOrEqualTo(1);
        assertThat(gate.currentCount(broadcastId, generation)).isZero();
    }

    @Test
    @DisplayName("R13.2: 결제 결과가 UNKNOWN(PAYMENT_IN_PROGRESS)인 주문은 TTL이 지나도 선점이 유지된다")
    void keepsHoldWhenPaymentResultIsUnknown() throws InterruptedException {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);
        long generation = pinRepository.findByBroadcastId(broadcastId).orElseThrow().getGeneration();
        OrderPlacement.PlacedOrder placed = liveOrderService.order(2L, broadcastId, productId, "it-r132-" + System.nanoTime());
        setOrderStatus(placed.orderNo(), "PAYMENT_IN_PROGRESS");

        Thread.sleep(1000);
        reconciler.reconcileAll();

        assertThat(gate.currentCount(broadcastId, generation)).isEqualTo(1);
    }

    @Test
    @DisplayName("R13: 결제가 확정(PAID)된 주문은 TTL이 지나도 영구히 선점을 유지한다")
    void permanentlyConfirmsPaidHold() throws InterruptedException {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);
        long generation = pinRepository.findByBroadcastId(broadcastId).orElseThrow().getGeneration();
        OrderPlacement.PlacedOrder placed = liveOrderService.order(3L, broadcastId, productId, "it-r13paid-" + System.nanoTime());
        setOrderStatus(placed.orderNo(), "PAID");

        Thread.sleep(1000);
        reconciler.reconcileAll();
        Thread.sleep(1000);
        reconciler.reconcileAll();

        assertThat(gate.currentCount(broadcastId, generation)).isEqualTo(1);
    }

    @Test
    @DisplayName("R14.1: 한정 수량의 마지막 1개가 확정되는 동안(동기) 매진(남은 수량 0) 이벤트가 방송된다")
    void broadcastsSoldOutSynchronouslyOnLastUnit() {
        long productId = newProduct();
        long broadcastId = newBroadcastWithPin(productId, 3);

        liveOrderService.order(4L, broadcastId, productId, "it-r141-1-" + System.nanoTime());
        liveOrderService.order(5L, broadcastId, productId, "it-r141-2-" + System.nanoTime());
        liveOrderService.order(6L, broadcastId, productId, "it-r141-3-" + System.nanoTime());

        ArgumentCaptor<LivePinEventView> captor = ArgumentCaptor.forClass(LivePinEventView.class);
        verify(broadcaster, atLeastOnce()).broadcast(captor.capture());
        boolean sawSoldOut = captor.getAllValues().stream()
                .anyMatch(e -> e.type() == LivePinEventType.QUANTITY_CHANGED
                        && e.remainingQuantity() != null && e.remainingQuantity() == 0);
        assertThat(sawSoldOut).isTrue();
    }
}
