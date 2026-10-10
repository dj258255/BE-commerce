package com.beomsu.becommerce.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R8·R9: {@link LivePinService}(소유권·"방송 중" 검증, 고정·해제·가격 변경, 시청자 발행)를
 * 실제 DB·WebSocket 없이 결정적으로 검증한다({@link LivePinBroadcaster}를 가짜로 바꾼다).
 */
class LivePinServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long SELLER_ID = 3L;
    private static final long BROADCAST_ID = 10L;

    private LivePinRepository pinRepository;
    private LiveBroadcastRepository broadcastRepository;
    private ProductLookup productLookup;
    private LivePinBroadcaster broadcaster;
    private LiveOrderGate gate;
    private LivePinService service;

    @BeforeEach
    void setUp() {
        pinRepository = mock(LivePinRepository.class);
        broadcastRepository = mock(LiveBroadcastRepository.class);
        productLookup = mock(ProductLookup.class);
        broadcaster = mock(LivePinBroadcaster.class);
        gate = mock(LiveOrderGate.class);   // currentCount 기본값 0 — 남은 수량은 한도 그대로
        service = new LivePinService(pinRepository, broadcastRepository, productLookup, broadcaster, gate,
                new LivePinCache(), java.time.Clock.fixed(T0, java.time.ZoneOffset.UTC));
        when(pinRepository.save(org.mockito.ArgumentMatchers.any(LivePin.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    private static LiveBroadcast liveBroadcast(long id, long sellerId) {
        LiveBroadcast b = LiveBroadcast.schedule(sellerId, "KEY1", "방송 제목", T0);
        ReflectionTestUtils.setField(b, "id", id);
        b.startOrResumePublish(T0);
        return b;
    }

    @Test
    @DisplayName("R8.1: LIVE 방송에 상품을 고정하면(특가 9,900원·한정 50개) 카드 정보가 응답에 담기고 시청자에게 PINNED 이벤트가 나간다")
    void pinningBroadcastsPinnedEventWithCardInfo() {
        when(broadcastRepository.findById(BROADCAST_ID)).thenReturn(Optional.of(liveBroadcast(BROADCAST_ID, SELLER_ID)));
        when(productLookup.exists(100L)).thenReturn(true);
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.empty());
        when(productLookup.findAll(List.of(100L))).thenReturn(List.of(new ProductLookup.Product(100L, "P1", 9_900L)));

        LivePinEventView view = service.pin(SELLER_ID, BROADCAST_ID, 100L, 9_900L, 50);

        assertThat(view.type()).isEqualTo(LivePinEventType.PINNED);
        assertThat(view.productId()).isEqualTo(100L);
        assertThat(view.productName()).isEqualTo("P1");
        assertThat(view.price()).isEqualTo(9_900L);
        assertThat(view.remainingQuantity()).isEqualTo(50);
        assertThat(view.seq()).isEqualTo(1L);
        verify(broadcaster).broadcast(view);
    }

    @Test
    @DisplayName("R8.2: P1이 고정된 상태에서 P2를 고정하면 같은 방송의 카드가 P2 하나로 바뀌고 "
            + "그 행동 하나에 시청자에게는 PINNED(P2) 이벤트 1건만 나간다")
    void pinningSecondProductReplacesFirstAndEmitsOneNewEvent() {
        when(broadcastRepository.findById(BROADCAST_ID)).thenReturn(Optional.of(liveBroadcast(BROADCAST_ID, SELLER_ID)));
        when(productLookup.exists(anyLong())).thenReturn(true);
        when(productLookup.findAll(List.of(100L))).thenReturn(List.of(new ProductLookup.Product(100L, "P1", 9_900L)));
        when(productLookup.findAll(List.of(200L))).thenReturn(List.of(new ProductLookup.Product(200L, "P2", 15_000L)));
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.empty());

        service.pin(SELLER_ID, BROADCAST_ID, 100L, 9_900L, 50); // Given: P1이 고정된 상태를 만든다
        ArgumentCaptor<LivePin> saved = ArgumentCaptor.forClass(LivePin.class);
        verify(pinRepository).save(saved.capture());
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(saved.getValue()));
        clearInvocations(broadcaster); // Given 단계의 발행은 지금 보려는 "When" 범위가 아니다

        LivePinEventView view = service.pin(SELLER_ID, BROADCAST_ID, 200L, 15_000L, 10); // When: P2를 고정

        assertThat(view.productId()).isEqualTo(200L);
        assertThat(view.seq()).isEqualTo(2L);
        verify(broadcaster, times(1)).broadcast(view); // P2 고정 1건뿐 — 별도 "P1 해제" 이벤트는 없다
        assertThat(saved.getValue().getProductId()).isEqualTo(200L); // 같은 행이 P2로 바뀌었다(P1은 더 없다)
    }

    @Test
    @DisplayName("R8: 고정하려는 상품이 카탈로그에 없으면 거절되고 이벤트도 안 나간다")
    void pinningNonexistentProductIsRejected() {
        when(broadcastRepository.findById(BROADCAST_ID)).thenReturn(Optional.of(liveBroadcast(BROADCAST_ID, SELLER_ID)));
        when(productLookup.exists(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.pin(SELLER_ID, BROADCAST_ID, 999L, 9_900L, 50))
                .isInstanceOf(LivePinException.class)
                .hasFieldOrPropertyWithValue("code", "PRODUCT_NOT_FOUND");
        verify(broadcaster, never()).broadcast(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("R8: 방송 중(LIVE)이 아니면(SCHEDULED) 고정이 거절된다")
    void pinningWhenBroadcastNotLiveIsRejected() {
        LiveBroadcast scheduled = LiveBroadcast.schedule(SELLER_ID, "KEY1", "방송 제목", T0); // 아직 LIVE 아님
        ReflectionTestUtils.setField(scheduled, "id", BROADCAST_ID);
        when(broadcastRepository.findById(BROADCAST_ID)).thenReturn(Optional.of(scheduled));

        assertThatThrownBy(() -> service.pin(SELLER_ID, BROADCAST_ID, 100L, 9_900L, 50))
                .isInstanceOf(LivePinException.class)
                .hasFieldOrPropertyWithValue("code", "BROADCAST_NOT_LIVE");
    }

    @Test
    @DisplayName("R8: 다른 판매자의 방송에는 고정할 수 없다(403)")
    void pinningOthersBroadcastIsForbidden() {
        when(broadcastRepository.findById(BROADCAST_ID)).thenReturn(Optional.of(liveBroadcast(BROADCAST_ID, 999L)));

        assertThatThrownBy(() -> service.pin(SELLER_ID, BROADCAST_ID, 100L, 9_900L, 50))
                .isInstanceOf(LiveBroadcastException.class)
                .hasFieldOrPropertyWithValue("code", "LIVE_BROADCAST_FORBIDDEN");
    }

    @Test
    @DisplayName("R9.1: 고정된 상품의 가격을 바꾸면 PRICE_CHANGED 이벤트가 새 가격·다음 seq로 나간다")
    void changingPriceBroadcastsPriceChangedEvent() {
        when(broadcastRepository.findById(BROADCAST_ID)).thenReturn(Optional.of(liveBroadcast(BROADCAST_ID, SELLER_ID)));
        LivePin pin = LivePin.forBroadcast(BROADCAST_ID, T0);
        pin.pin(100L, 9_900L, 50, T0); // seq=1
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pin));
        when(productLookup.findAll(List.of(100L))).thenReturn(List.of(new ProductLookup.Product(100L, "P1", 9_900L)));

        LivePinEventView view = service.changePrice(SELLER_ID, BROADCAST_ID, 7_900L);

        assertThat(view.type()).isEqualTo(LivePinEventType.PRICE_CHANGED);
        assertThat(view.price()).isEqualTo(7_900L);
        assertThat(view.seq()).isEqualTo(2L);
        verify(broadcaster).broadcast(view);
    }

    @Test
    @DisplayName("R8: 해제는 멱등 — 고정된 상품이 없는데 해제를 호출해도 새 이벤트를 내지 않는다")
    void unpinWithNothingPinnedDoesNotBroadcast() {
        when(broadcastRepository.findById(BROADCAST_ID)).thenReturn(Optional.of(liveBroadcast(BROADCAST_ID, SELLER_ID)));
        when(pinRepository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.empty());

        LivePinEventView view = service.unpin(SELLER_ID, BROADCAST_ID);

        assertThat(view.type()).isEqualTo(LivePinEventType.UNPINNED);
        verify(broadcaster, never()).broadcast(org.mockito.ArgumentMatchers.any());
    }
}
