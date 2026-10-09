package com.beomsu.becommerce.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10·R11·R12·R13·R14: {@link LiveOrderService}(지금 고정 상태로 가능 여부·가격 판정, Redis
 * 한정 수량 게이트, commerce 주문 생성 위임, 남은 수량·매진 방송)를 실제 DB·Redis 없이
 * 결정적으로 검증한다. 실제 동시성(R12.1)·재고정 세대 분리·미결제 반환(R13)·매진 전환(R14)은
 * {@code commerce}의 {@code LiveOrderConcurrencyTest}(Testcontainers, 로컬·CI)와
 * {@code LiveOrderConcurrencySandboxTest}(이 샌드박스의 실 MySQL·Redis)가 확인한다 — 이
 * 테스트는 호출 순서·분기 로직·방송 내용만 본다.
 */
class LiveOrderServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long BROADCAST_ID = 10L;
    private static final long USER_ID = 1L;
    private static final long PRODUCT_ID = 100L;
    private static final String IDEM_KEY = "idem-1";
    private static final long GENERATION_1 = 1L;   // pin()을 한 번만 불렀을 때의 세대

    private LivePinRepository pinRepository;
    private OrderPlacement orderPlacement;
    private LiveOrderGate gate;
    private LivePinBroadcaster broadcaster;
    private LiveOrderService service;

    @BeforeEach
    void setUp() {
        pinRepository = mock(LivePinRepository.class);
        orderPlacement = mock(OrderPlacement.class);
        gate = mock(LiveOrderGate.class);
        broadcaster = mock(LivePinBroadcaster.class);
        service = new LiveOrderService(pinRepository, orderPlacement, gate, broadcaster,
                java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
    }

    private static LivePin pinned(long productId, long price, int limitedQuantity) {
        LivePin pin = LivePin.forBroadcast(BROADCAST_ID, T0);
        pin.pin(productId, price, limitedQuantity, T0);
        return pin;
    }

    @Test
    @DisplayName("R10.1·R11.1: 클라이언트는 가격을 보내지 않는다 — 서버는 지금 고정된 특가(9,900원)로 "
            + "장바구니 없이 주문 1건을 만들고, 결제 화면이 쓸 orderNo·totalAmount·expiresAt을 그대로 돌려준다")
    void usesServerPinnedPriceNotClientValue() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, GENERATION_1, 50, IDEM_KEY)).thenReturn(true);
        when(gate.currentCount(BROADCAST_ID, GENERATION_1)).thenReturn(1);
        OrderPlacement.PlacedOrder placed = new OrderPlacement.PlacedOrder("ORD-1", 9_900L, T0.plusSeconds(1800));
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY)).thenReturn(placed);

        OrderPlacement.PlacedOrder result = service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY);

        assertThat(result.orderNo()).isEqualTo("ORD-1");           // 장바구니 없이 주문 1건 — 결제로 이어질 id
        assertThat(result.totalAmount()).isEqualTo(9_900L);
        assertThat(result.expiresAt()).isEqualTo(T0.plusSeconds(1800));
        verify(orderPlacement).place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY); // 9,900원 그대로 — 클라이언트 값이 끼어들 자리가 없다
    }

    @Test
    @DisplayName("R10.2: 고정이 해제된 직후라면(고정 자체가 없음) 409로 거절되고 주문·게이트 호출이 없다")
    void nothingPinnedIsRejectedWithoutCallingGateOrOrderPlacement() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(LiveOrderException.class)
                .hasFieldOrPropertyWithValue("code", "NOTHING_PINNED");
        verify(gate, never()).tryReserve(anyLong(), anyLong(), anyInt(), anyString());
        verify(orderPlacement, never()).place(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("R10.2: 지금 고정된 상품이 요청과 다르면(카드가 바뀐 뒤 늦게 누른 경우) 409로 거절되고 주문이 생성되지 않는다")
    void pinnedToDifferentProductIsRejected() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(200L, 15_000L, 10)));

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(LiveOrderException.class)
                .hasFieldOrPropertyWithValue("code", "NOTHING_PINNED");
        verify(orderPlacement, never()).place(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("R12·R15: Redis 선점(TTL) 실패(매진)면 commerce 주문 생성을 호출하지 않고 즉시 거절한다")
    void gateRejectionSkipsOrderPlacementEntirely() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, GENERATION_1, 50, IDEM_KEY)).thenReturn(false);

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(LiveOrderException.class)
                .hasFieldOrPropertyWithValue("code", "LIMITED_QUANTITY_SOLD_OUT");
        verify(orderPlacement, never()).place(anyLong(), anyLong(), anyLong(), anyString()); // 결제 호출 없이 거절(R15)
        verify(broadcaster, never()).broadcast(any());   // 거절은 방송하지 않는다 — 실제로 바뀐 게 없다
    }

    @Test
    @DisplayName("R12: Redis는 선점했는데 commerce 주문 생성이 실패하면 선점을 즉시 돌려준다(슬롯 낭비 방지)")
    void releasesHoldWhenOrderPlacementFails() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, GENERATION_1, 50, IDEM_KEY)).thenReturn(true);
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY))
                .thenThrow(new RuntimeException("PRODUCT_NOT_FOUND"));

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(RuntimeException.class);
        verify(gate).release(BROADCAST_ID, GENERATION_1, IDEM_KEY);
        verify(broadcaster, never()).broadcast(any());   // 실패한 시도는 방송하지 않는다(순간 반짝임 방지)
    }

    @Test
    @DisplayName("R12(재고정 결함 수정): 같은 방송에서 완판 후 다시 고정하면(세대가 올라가면) "
            + "게이트는 새 세대 번호로 호출된다 — 이전 드롭의 선점과 Redis 키가 섞이지 않는다")
    void rePinningBumpsGenerationPassedToGate() {
        LivePin pin = LivePin.forBroadcast(BROADCAST_ID, T0);
        pin.pin(PRODUCT_ID, 9_900L, 50, T0);     // 1번째 드롭(완판됐다고 가정) — generation=1
        pin.unpin(T0);
        pin.pin(PRODUCT_ID, 7_900L, 30, T0);     // 같은 상품을 다시 고정(2번째 드롭) — generation=2
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pin));
        when(gate.tryReserve(BROADCAST_ID, 2L, 30, IDEM_KEY)).thenReturn(true);
        when(gate.currentCount(BROADCAST_ID, 2L)).thenReturn(1);
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 7_900L, IDEM_KEY))
                .thenReturn(new OrderPlacement.PlacedOrder("ORD-2", 7_900L, T0.plusSeconds(1800)));

        service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY);

        verify(gate).tryReserve(BROADCAST_ID, 2L, 30, IDEM_KEY);   // 세대 1이 아니라 2로 호출됐다
        verify(gate, never()).tryReserve(BROADCAST_ID, 1L, 30, IDEM_KEY);
    }

    @Test
    @DisplayName("R13.1·R14.1: 주문 성공 시 지금 홀드 수를 기록하고, 갱신된 남은 수량을 즉시 방송한다")
    void recordsOrderAndBroadcastsRemainingQuantityOnSuccess() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, GENERATION_1, 50, IDEM_KEY)).thenReturn(true);
        when(gate.currentCount(BROADCAST_ID, GENERATION_1)).thenReturn(3);   // 이 주문을 포함해 3명째
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY))
                .thenReturn(new OrderPlacement.PlacedOrder("ORD-3", 9_900L, T0.plusSeconds(1800)));

        service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY);

        verify(gate).recordOrder(BROADCAST_ID, GENERATION_1, IDEM_KEY, "ORD-3");   // R13: 나중에 결제 상태를 물을 수 있게
        ArgumentCaptor<LivePinEventView> captor = ArgumentCaptor.forClass(LivePinEventView.class);
        verify(broadcaster).broadcast(captor.capture());
        LivePinEventView event = captor.getValue();
        assertThat(event.type()).isEqualTo(LivePinEventType.QUANTITY_CHANGED);
        assertThat(event.productId()).isEqualTo(PRODUCT_ID);
        assertThat(event.remainingQuantity()).isEqualTo(47);   // 50 - 3
    }

    @Test
    @DisplayName("R14.1: 한정 수량의 마지막 1개가 확정되면(남은 수량 0) 매진 이벤트가 방송되고 운영 로그에 남는다")
    void broadcastsSoldOutWhenLastUnitReserved() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, GENERATION_1, 50, IDEM_KEY)).thenReturn(true);
        when(gate.currentCount(BROADCAST_ID, GENERATION_1)).thenReturn(50);   // 마지막 1개
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY))
                .thenReturn(new OrderPlacement.PlacedOrder("ORD-50", 9_900L, T0.plusSeconds(1800)));

        service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY);

        ArgumentCaptor<LivePinEventView> captor = ArgumentCaptor.forClass(LivePinEventView.class);
        verify(broadcaster).broadcast(captor.capture());
        assertThat(captor.getValue().remainingQuantity()).isZero();   // 0 = 매진(R14, 별도 SOLD_OUT 타입을 쓰지 않는다)
    }

    @Test
    @DisplayName("R13(어긋남 처리): 주문은 성공했는데 선점 기록(recordOrder)이 실패해도 "
            + "선점을 되돌리지 않고 주문 결과를 그대로 돌려준다 — 되돌리면 MySQL엔 진짜 주문이 "
            + "있는데 Redis는 그 슬롯을 비워 한도 초과로 이어진다(ADR-085)")
    void doesNotReleaseWhenRecordOrderFailsAfterOrderSucceeds() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, GENERATION_1, 50, IDEM_KEY)).thenReturn(true);
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY))
                .thenReturn(new OrderPlacement.PlacedOrder("ORD-OK", 9_900L, T0.plusSeconds(1800)));
        doThrow(new RuntimeException("redis blip")).when(gate)
                .recordOrder(BROADCAST_ID, GENERATION_1, IDEM_KEY, "ORD-OK");

        OrderPlacement.PlacedOrder result = service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY);

        assertThat(result.orderNo()).isEqualTo("ORD-OK");   // 고객은 주문을 잃지 않는다
        verify(gate, never()).release(anyLong(), anyLong(), anyString());
    }

    @Test
    @DisplayName("R14.2: 매진 상태(게이트 거절)에서 주문을 호출하면 거절되고 주문·결제가 진행되지 않는다")
    void soldOutRejectsOrderWithoutPaymentCall() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, GENERATION_1, 50, IDEM_KEY)).thenReturn(false);   // 이미 50/50

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(LiveOrderException.class)
                .hasFieldOrPropertyWithValue("code", "LIMITED_QUANTITY_SOLD_OUT");
        verify(orderPlacement, never()).place(anyLong(), anyLong(), anyLong(), anyString());
    }
}
