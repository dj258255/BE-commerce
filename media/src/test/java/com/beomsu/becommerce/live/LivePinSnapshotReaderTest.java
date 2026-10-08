package com.beomsu.becommerce.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R9.2: {@link LivePinSnapshotReader}가 시청자 접속(최초 입장·재연결 구분 없음)마다 돌려주는
 * "지금 상태" 스냅샷을 검증한다 — WebSocket 없이, 결정적으로.
 */
class LivePinSnapshotReaderTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long BROADCAST_ID = 5L;

    @Test
    @DisplayName("R9.2: 상품 P1이 특가 7,900원·남은 수량 23으로 고정된 방송에 재연결하면 "
            + "그 스냅샷(P1, 7900원, 23, 최신 seq)을 그대로 받는다")
    void snapshotReflectsCurrentPinWithLatestSeq() {
        LivePinRepository repository = mock(LivePinRepository.class);
        ProductLookup productLookup = mock(ProductLookup.class);
        LivePin pin = LivePin.forBroadcast(BROADCAST_ID, T0);
        pin.pin(1L, 9_900L, 23, T0.plusSeconds(1)); // seq=1
        pin.changePrice(7_900L, T0.plusSeconds(2)); // seq=2, 수량은 23 그대로
        when(repository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pin));
        when(productLookup.findAll(List.of(1L))).thenReturn(List.of(new ProductLookup.Product(1L, "P1", 9_900L)));
        LivePinSnapshotReader reader = new LivePinSnapshotReader(repository, productLookup);

        LivePinEventView snapshot = reader.snapshot(BROADCAST_ID);

        assertThat(snapshot.productId()).isEqualTo(1L);
        assertThat(snapshot.productName()).isEqualTo("P1");
        assertThat(snapshot.price()).isEqualTo(7_900L);
        assertThat(snapshot.remainingQuantity()).isEqualTo(23);
        assertThat(snapshot.seq()).isEqualTo(2L);
        assertThat(snapshot.effectiveAt()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    @DisplayName("R9.2 경계: 아직 한 번도 고정된 적 없는 방송은 UNPINNED·seq=0 스냅샷을 받는다")
    void snapshotWithNoPinRowReturnsEmptyUnpinned() {
        LivePinRepository repository = mock(LivePinRepository.class);
        ProductLookup productLookup = mock(ProductLookup.class);
        when(repository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.empty());
        LivePinSnapshotReader reader = new LivePinSnapshotReader(repository, productLookup,
                Clock.fixed(T0, ZoneOffset.UTC));

        LivePinEventView snapshot = reader.snapshot(BROADCAST_ID);

        assertThat(snapshot.type()).isEqualTo(LivePinEventType.UNPINNED);
        assertThat(snapshot.productId()).isNull();
        assertThat(snapshot.seq()).isZero();
    }

    @Test
    @DisplayName("R9.2 경계: 고정했다가 해제된 방송은 UNPINNED지만 그동안 쌓인 seq는 유지한다")
    void snapshotAfterUnpinKeepsAccumulatedSeq() {
        LivePinRepository repository = mock(LivePinRepository.class);
        ProductLookup productLookup = mock(ProductLookup.class);
        LivePin pin = LivePin.forBroadcast(BROADCAST_ID, T0);
        pin.pin(1L, 9_900L, 23, T0.plusSeconds(1)); // seq=1
        pin.unpin(T0.plusSeconds(2)); // seq=2
        when(repository.findByBroadcastId(BROADCAST_ID)).thenReturn(Optional.of(pin));
        LivePinSnapshotReader reader = new LivePinSnapshotReader(repository, productLookup);

        LivePinEventView snapshot = reader.snapshot(BROADCAST_ID);

        assertThat(snapshot.type()).isEqualTo(LivePinEventType.UNPINNED);
        assertThat(snapshot.productId()).isNull();
        assertThat(snapshot.seq()).isEqualTo(2L);
    }
}
