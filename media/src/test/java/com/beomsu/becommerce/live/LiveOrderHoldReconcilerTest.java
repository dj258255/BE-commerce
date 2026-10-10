package com.beomsu.becommerce.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R13: {@link LiveOrderHoldReconciler}(미결제 선점 반환 · 결제 UNKNOWN 중 유지)를 실제
 * Redis·시간 경과 없이, {@link MutableClock}으로 "5분이 지났다"를 결정적으로 재현해 검증한다.
 * 실 Redis·MySQL로의 end-to-end 확인은 commerce의 {@code LiveOrderConcurrencySandboxTest}가 한다.
 */
class LiveOrderHoldReconcilerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long BROADCAST_ID = 10L;
    private static final long PRODUCT_ID = 100L;
    private static final long GENERATION = 1L;

    private LivePinRepository pinRepository;
    private LiveOrderGate gate;
    private OrderPaymentStatus orderPaymentStatus;
    private LivePinBroadcaster broadcaster;
    private MutableClock clock;
    private LiveOrderHoldReconciler reconciler;

    @BeforeEach
    void setUp() {
        pinRepository = mock(LivePinRepository.class);
        gate = mock(LiveOrderGate.class);
        orderPaymentStatus = mock(OrderPaymentStatus.class);
        broadcaster = mock(LivePinBroadcaster.class);
        clock = new MutableClock(T0);
        reconciler = new LiveOrderHoldReconciler(pinRepository, gate, orderPaymentStatus, broadcaster, clock);
    }

    private static LivePin pinned(int limitedQuantity) {
        LivePin pin = LivePin.forBroadcast(BROADCAST_ID, T0);
        pin.pin(PRODUCT_ID, 9_900L, limitedQuantity, T0);
        return pin;
    }

    @Test
    @DisplayName("R13.1: 선점 후 5분이 지난(expiredHolds 후보) 미결제(OTHER) 홀드는 즉시 반환되고 "
            + "남은 수량 갱신이 방송된다")
    void releasesExpiredUnpaidHoldAndBroadcastsRemaining() {
        LivePin pin = pinned(50);
        when(pinRepository.findByProductIdIsNotNull()).thenReturn(List.of(pin));
        clock.set(T0.plus(java.time.Duration.ofMinutes(5).plusSeconds(1)));
        when(gate.expiredHolds(BROADCAST_ID, GENERATION, clock.millis())).thenReturn(List.of("idem-abandoned"));
        when(gate.orderNoOf(BROADCAST_ID, GENERATION, "idem-abandoned")).thenReturn("ORD-ABANDONED");
        when(orderPaymentStatus.outcomeOf("ORD-ABANDONED")).thenReturn(OrderPaymentStatus.Outcome.OTHER);
        when(gate.currentCount(BROADCAST_ID, GENERATION)).thenReturn(49);   // 하나 돌아가 49/50

        int released = reconciler.reconcileAll();

        assertThat(released).isEqualTo(1);
        verify(gate).release(BROADCAST_ID, GENERATION, "idem-abandoned");
        verify(gate, never()).confirmPermanently(anyLong(), anyLong(), anyString());
        ArgumentCaptor<LivePinEventView> captor = ArgumentCaptor.forClass(LivePinEventView.class);
        verify(broadcaster).broadcast(captor.capture());
        assertThat(captor.getValue().type()).isEqualTo(LivePinEventType.QUANTITY_CHANGED);
        assertThat(captor.getValue().remainingQuantity()).isEqualTo(1);   // 50 - 49
    }

    @Test
    @DisplayName("R13.2: 결제 결과가 UNKNOWN(IN_PROGRESS)인 홀드는 5분이 지나도 반환되지 않고 그대로 유지된다")
    void keepsHoldWhenPaymentResultIsUnknown() {
        LivePin pin = pinned(50);
        when(pinRepository.findByProductIdIsNotNull()).thenReturn(List.of(pin));
        clock.set(T0.plus(java.time.Duration.ofMinutes(6)));
        when(gate.expiredHolds(BROADCAST_ID, GENERATION, clock.millis())).thenReturn(List.of("idem-unknown"));
        when(gate.orderNoOf(BROADCAST_ID, GENERATION, "idem-unknown")).thenReturn("ORD-UNKNOWN");
        when(orderPaymentStatus.outcomeOf("ORD-UNKNOWN")).thenReturn(OrderPaymentStatus.Outcome.IN_PROGRESS);

        int released = reconciler.reconcileAll();

        assertThat(released).isZero();
        verify(gate, never()).release(anyLong(), anyLong(), anyString());
        verify(gate, never()).confirmPermanently(anyLong(), anyLong(), anyString());
        verify(broadcaster, never()).broadcast(any());   // 아무것도 안 바뀌었으니 방송하지 않는다
    }

    @Test
    @DisplayName("R13: 결제가 확정(PAID)된 홀드는 영구화된다 — 반환되지 않고, 더 이상 평가 후보가 되지 않는다")
    void confirmsPaidHoldPermanently() {
        LivePin pin = pinned(50);
        when(pinRepository.findByProductIdIsNotNull()).thenReturn(List.of(pin));
        clock.set(T0.plus(java.time.Duration.ofMinutes(6)));
        when(gate.expiredHolds(BROADCAST_ID, GENERATION, clock.millis())).thenReturn(List.of("idem-paid"));
        when(gate.orderNoOf(BROADCAST_ID, GENERATION, "idem-paid")).thenReturn("ORD-PAID");
        when(orderPaymentStatus.outcomeOf("ORD-PAID")).thenReturn(OrderPaymentStatus.Outcome.PAID);

        int released = reconciler.reconcileAll();

        assertThat(released).isZero();
        verify(gate).confirmPermanently(BROADCAST_ID, GENERATION, "idem-paid");
        verify(gate, never()).release(BROADCAST_ID, GENERATION, "idem-paid");
        verify(broadcaster, never()).broadcast(any());   // 확정은 수량을 바꾸지 않는다(이미 세어져 있었다)
    }

    @Test
    @DisplayName("R13.1: 아직 5분이 안 지난 홀드는 후보에 없으므로(expiredHolds가 빈 목록) 평가 자체를 하지 않는다")
    void doesNothingWhenNoCandidatesAreExpiredYet() {
        LivePin pin = pinned(50);
        when(pinRepository.findByProductIdIsNotNull()).thenReturn(List.of(pin));
        when(gate.expiredHolds(BROADCAST_ID, GENERATION, clock.millis())).thenReturn(List.of());

        int released = reconciler.reconcileAll();

        assertThat(released).isZero();
        verify(orderPaymentStatus, never()).outcomeOf(any());
        verify(broadcaster, never()).broadcast(any());
    }
}
