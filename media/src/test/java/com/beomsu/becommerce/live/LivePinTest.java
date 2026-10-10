package com.beomsu.becommerce.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R8·R9: {@link LivePin}(방송당 한 행)의 고정·해제·가격 변경과 seq·effectiveAt을 검증한다. */
class LivePinTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    @DisplayName("R8.1: 고정이 없는 방송에 상품을 고정하면 특가·한정 수량이 저장되고 seq가 1이 된다")
    void pinningSetsFieldsAndIncrementsSeq() {
        LivePin pin = LivePin.forBroadcast(1L, T0);

        LivePinEventType type = pin.pin(100L, 9_900L, 50, T0.plusSeconds(1));

        assertThat(type).isEqualTo(LivePinEventType.PINNED);
        assertThat(pin.getProductId()).isEqualTo(100L);
        assertThat(pin.getPrice()).isEqualTo(9_900L);
        assertThat(pin.getLimitedQuantity()).isEqualTo(50);
        assertThat(pin.getSeq()).isEqualTo(1L);
        assertThat(pin.getEffectiveAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    @DisplayName("R8.2: P1이 고정된 상태에서 P2를 고정하면 그 자리가 P2로 바뀌고(동시 1개) seq가 또 올라간다")
    void pinningNewProductReplacesPrevious() {
        LivePin pin = LivePin.forBroadcast(1L, T0);
        pin.pin(100L, 9_900L, 50, T0.plusSeconds(1)); // P1

        pin.pin(200L, 15_000L, 10, T0.plusSeconds(2)); // P2

        assertThat(pin.getProductId()).isEqualTo(200L); // P1은 더 이상 안 보인다
        assertThat(pin.getPrice()).isEqualTo(15_000L);
        assertThat(pin.getLimitedQuantity()).isEqualTo(10);
        assertThat(pin.getSeq()).isEqualTo(2L);
        assertThat(pin.isPinned()).isTrue();
    }

    @Test
    @DisplayName("R8 경계: 고정 해제는 멱등 — 고정된 적 없는 상태에서 해제해도 seq가 그대로다")
    void unpinIsIdempotentWhenNothingPinned() {
        LivePin pin = LivePin.forBroadcast(1L, T0);

        boolean changed = pin.unpin(T0.plusSeconds(1));

        assertThat(changed).isFalse();
        assertThat(pin.getSeq()).isZero();
        assertThat(pin.getEffectiveAt()).isNull();
    }

    @Test
    @DisplayName("R8: 고정 해제하면 상품 정보가 전부 지워지고 seq가 오른다")
    void unpinClearsFieldsAndIncrementsSeq() {
        LivePin pin = LivePin.forBroadcast(1L, T0);
        pin.pin(100L, 9_900L, 50, T0.plusSeconds(1));

        boolean changed = pin.unpin(T0.plusSeconds(2));

        assertThat(changed).isTrue();
        assertThat(pin.isPinned()).isFalse();
        assertThat(pin.getProductId()).isNull();
        assertThat(pin.getPrice()).isNull();
        assertThat(pin.getLimitedQuantity()).isNull();
        assertThat(pin.getSeq()).isEqualTo(2L);
        assertThat(pin.getEffectiveAt()).isEqualTo(T0.plusSeconds(2));
    }

    @Test
    @DisplayName("R9.1: 가격 변경은 고정된 상품·수량은 그대로 두고 price·seq·effectiveAt만 갱신한다")
    void changePriceUpdatesPriceAndSeqOnly() {
        LivePin pin = LivePin.forBroadcast(1L, T0);
        pin.pin(100L, 9_900L, 50, T0.plusSeconds(1));

        pin.changePrice(7_900L, T0.plusSeconds(10));

        assertThat(pin.getProductId()).isEqualTo(100L);
        assertThat(pin.getPrice()).isEqualTo(7_900L);
        assertThat(pin.getLimitedQuantity()).isEqualTo(50);
        assertThat(pin.getSeq()).isEqualTo(2L);
        assertThat(pin.getEffectiveAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    @DisplayName("R9 경계: 고정된 상품이 없는데 가격을 바꾸려 하면 거절된다")
    void changePriceWithoutPinIsRejected() {
        LivePin pin = LivePin.forBroadcast(1L, T0);

        assertThatThrownBy(() -> pin.changePrice(7_900L, T0))
                .isInstanceOf(LivePinException.class)
                .hasFieldOrPropertyWithValue("code", "NOTHING_PINNED");
    }

    @Test
    @DisplayName("R8 경계: 방송 특가가 0 이하면 거절된다")
    void pinningWithNonPositivePriceIsRejected() {
        LivePin pin = LivePin.forBroadcast(1L, T0);

        assertThatThrownBy(() -> pin.pin(100L, 0L, 50, T0))
                .isInstanceOf(LivePinException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_PRICE");
    }

    @Test
    @DisplayName("R8 경계: 한정 수량이 0 이하면 거절된다")
    void pinningWithNonPositiveQuantityIsRejected() {
        LivePin pin = LivePin.forBroadcast(1L, T0);

        assertThatThrownBy(() -> pin.pin(100L, 9_900L, 0, T0))
                .isInstanceOf(LivePinException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_QUANTITY");
    }
}
