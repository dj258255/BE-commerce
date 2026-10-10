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
 * <p><b>이 테스트 프로세스 밖에, 이미 떠 있는 commerce 서비스와 경합한다.</b> b-studio
 * 샌드박스는 compose.b-studio.yaml로 commerce를 {@code local} 프로파일로 띄워 두고, 그
 * 프로파일은 {@code app.live.order.hold-recovery.enabled=true}라 그 서비스 프로세스 안에서
 * {@code LiveOrderHoldRecoveryScheduler}가 5초마다 진짜로 {@code reconcileAll()}을 돈다.
 * 이 테스트는 그 서비스와 **같은 MySQL·Redis**({@code SPRING_DATASOURCE_URL=jdbc:mysql://mysql:...})
 * 에 직접 붙으므로, 이 테스트가 100ms TTL로 만든 선점이 만료된 뒤 이 테스트가 수동으로
 * {@code reconciler.reconcileAll()}을 부르기 <i>전에</i> 그 서비스의 스케줄러가 먼저 돌면
 * 서비스 쪽이 이 테스트의 선점을 먼저 반환해 버려 이 테스트의 수동 호출은 0건을 돌려받는다
 * (released=0, 경험적으로 4회 중 1회꼴 재현됨). 이 테스트 JVM 안의
 * {@code app.live.order.hold-recovery.enabled}를 꺼도(아래 프로퍼티) 소용없다 — 경합 상대는
 * 이 테스트 컨텍스트가 아니라 **별도 프로세스로 떠 있는 서비스**이기 때문이다. 그래서 진짜
 * 고치는 지점은 "같은 Redis를 쓰지 않게" 만드는 것이다 — {@code spring.data.redis.database}를
 * 서비스가 쓰는 기본값(0, compose.b-studio.yaml이 DATABASE를 안 정해서 기본값)과 다른 번호로
 * 오버라이드해, {@link LiveOrderGate}의 ZSET 키({@code live:pin:...})가 서비스 쪽 Redis
 * 논리 DB와 아예 분리된 공간에 쓰이게 한다 — 서비스의 스케줄러는 자기 DB(0)에서 그 키를 보지
 * 못해 손대지 못한다. MySQL(핀·주문 행)은 여전히 서비스와 같은 테이블을 보지만, 서비스
 * 스케줄러가 핀을 찾아도 Redis 쪽에 대응하는 홀드가(자기 DB 기준으로) 없으니 할 일이 없다.
 * {@code app.live.order.hold-recovery.enabled=false}는 이 테스트 자신의 컨텍스트에 중복으로
 * 뜰 스케줄러 빈을 막는 방어적 설정으로 남겨 둔다(이 테스트는 스케줄러가 아니라
 * {@code reconciler.reconcileAll()}을 직접 불러서 본다).
 *
 * <p>{@code LivePinBroadcaster}는 {@code @MockBean}으로 바꾸지 않는다 — 그 인터페이스를
 * 구현하는 {@code LivePinWebSocketHandler}를 {@code LivePinWebSocketConfig}가 콘크리트
 * 타입으로 직접 의존해서, 인터페이스를 모킹하면 그 빈이 통째로 사라져 컨텍스트가 안 뜬다.
 * 대신 {@link LiveOrderService}·{@link LiveOrderHoldReconciler}를 이 테스트에서 직접
 * 생성해(다른 의존은 전부 실제 스프링 빈을 그대로 주입) 방송 호출만 가짜로 본다 — 둘 다
 * public 생성자가 있어 가능하다.
 */
@EnabledIfEnvironmentVariable(named = "SPRING_DATASOURCE_URL", matches = "jdbc:mysql://mysql:.*")
@SpringBootTest(properties = {
        "app.live.order.hold-ttl=100ms",
        "app.live.order.hold-recovery.enabled=false",
        "spring.data.redis.database=1"
})
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
