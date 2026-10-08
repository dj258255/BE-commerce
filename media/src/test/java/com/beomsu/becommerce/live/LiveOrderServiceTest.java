package com.beomsu.becommerce.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10·R11·R12: {@link LiveOrderService}(지금 고정 상태로 가능 여부·가격 판정, Redis 한정 수량
 * 게이트, commerce 주문 생성 위임)를 실제 DB·Redis 없이 결정적으로 검증한다. 실제 동시성(R12.1,
 * 확정 합계 = N)은 {@code commerce}의 {@code LiveOrderConcurrencyTest}(Testcontainers MySQL·Redis)가
 * 확인한다 — 이 테스트는 호출 순서·분기 로직만 본다.
 */
class LiveOrderServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long BROADCAST_ID = 10L;
    private static final long USER_ID = 1L;
    private static final long PRODUCT_ID = 100L;
    private static final String IDEM_KEY = "idem-1";

    private LivePinRepository pinRepository;
    private OrderPlacement orderPlacement;
    private LiveOrderGate gate;
    private LiveOrderService service;

    @BeforeEach
    void setUp() {
        pinRepository = mock(LivePinRepository.class);
        orderPlacement = mock(OrderPlacement.class);
        gate = mock(LiveOrderGate.class);
        service = new LiveOrderService(pinRepository, orderPlacement, gate);
    }

    private static LivePin pinned(long productId, long price, int limitedQuantity) {
        LivePin pin = LivePin.forBroadcast(BROADCAST_ID, T0);
        pin.pin(productId, price, limitedQuantity, T0);
        return pin;
    }

    @Test
    @DisplayName("R10.1: 클라이언트는 가격을 보내지 않는다 — 서버는 지금 고정된 특가(9,900원)로만 commerce에 주문을 요청한다")
    void usesServerPinnedPriceNotClientValue() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, 50, IDEM_KEY)).thenReturn(true);
        OrderPlacement.PlacedOrder placed = new OrderPlacement.PlacedOrder("ORD-1", 9_900L, T0.plusSeconds(1800));
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY)).thenReturn(placed);

        OrderPlacement.PlacedOrder result = service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY);

        assertThat(result.orderNo()).isEqualTo("ORD-1");
        assertThat(result.totalAmount()).isEqualTo(9_900L);
        verify(orderPlacement).place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY); // 9,900원 그대로 — 클라이언트 값이 끼어들 자리가 없다
    }

    @Test
    @DisplayName("R10.2: 고정이 해제된 직후라면(고정 자체가 없음) 409로 거절되고 주문·게이트 호출이 없다")
    void nothingPinnedIsRejectedWithoutCallingGateOrOrderPlacement() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(LiveOrderException.class)
                .hasFieldOrPropertyWithValue("code", "NOTHING_PINNED");
        verify(gate, never()).tryReserve(anyLong(), anyInt(), anyString());
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
        when(gate.tryReserve(BROADCAST_ID, 50, IDEM_KEY)).thenReturn(false);

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(LiveOrderException.class)
                .hasFieldOrPropertyWithValue("code", "LIMITED_QUANTITY_SOLD_OUT");
        verify(orderPlacement, never()).place(anyLong(), anyLong(), anyLong(), anyString()); // 결제 호출 없이 거절(R15)
    }

    @Test
    @DisplayName("R12: Redis는 선점했는데 commerce 주문 생성이 실패하면 선점을 즉시 돌려준다(슬롯 낭비 방지)")
    void releasesHoldWhenOrderPlacementFails() {
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pinned(PRODUCT_ID, 9_900L, 50)));
        when(gate.tryReserve(BROADCAST_ID, 50, IDEM_KEY)).thenReturn(true);
        when(orderPlacement.place(USER_ID, PRODUCT_ID, 9_900L, IDEM_KEY))
                .thenThrow(new RuntimeException("PRODUCT_NOT_FOUND"));

        assertThatThrownBy(() -> service.order(USER_ID, BROADCAST_ID, PRODUCT_ID, IDEM_KEY))
                .isInstanceOf(RuntimeException.class);
        verify(gate).release(BROADCAST_ID, IDEM_KEY);
    }
}
