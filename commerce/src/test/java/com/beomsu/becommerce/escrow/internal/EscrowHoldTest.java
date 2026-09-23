package com.beomsu.becommerce.escrow.internal;

import com.beomsu.becommerce.escrow.internal.EscrowStatus;
import com.beomsu.becommerce.escrow.internal.EscrowHold;
import com.beomsu.becommerce.escrow.internal.EscrowException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EscrowHoldTest {

    private EscrowHold heldHold() {
        Instant now = Instant.now();
        return EscrowHold.hold("ord-1", 20_000, now, now.plusSeconds(600));
    }

    @Test
    @DisplayName("생성 직후: HELD, resolvedAt=null, 금액/시각 보존")
    void newHoldIsHeld() {
        Instant heldAt = Instant.now();
        Instant autoRelease = heldAt.plusSeconds(600);
        EscrowHold h = EscrowHold.hold("ord-1", 20_000, heldAt, autoRelease);

        assertThat(h.getStatus()).isEqualTo(EscrowStatus.HELD);
        assertThat(h.getAmount()).isEqualTo(20_000);
        assertThat(h.getHeldAt()).isEqualTo(heldAt);
        assertThat(h.getAutoReleaseAt()).isEqualTo(autoRelease);
        assertThat(h.getResolvedAt()).isNull();
        assertThat(h.isHeld()).isTrue();
    }

    @Test
    @DisplayName("release: HELD → RELEASED, resolvedAt 기록")
    void releaseTransitions() {
        EscrowHold h = heldHold();
        Instant now = Instant.now();

        h.release(now);

        assertThat(h.getStatus()).isEqualTo(EscrowStatus.RELEASED);
        assertThat(h.getResolvedAt()).isEqualTo(now);
        assertThat(h.isHeld()).isFalse();
    }

    @Test
    @DisplayName("refund: HELD → REFUNDED, resolvedAt 기록")
    void refundTransitions() {
        EscrowHold h = heldHold();
        Instant now = Instant.now();

        h.refund(now);

        assertThat(h.getStatus()).isEqualTo(EscrowStatus.REFUNDED);
        assertThat(h.getResolvedAt()).isEqualTo(now);
        assertThat(h.isHeld()).isFalse();
    }

    @Test
    @DisplayName("가드: 이미 RELEASED면 다시 release 불가 → INVALID_ESCROW_STATE")
    void releaseAfterReleaseRejected() {
        EscrowHold h = heldHold();
        h.release(Instant.now());

        assertThatThrownBy(() -> h.release(Instant.now()))
                .isInstanceOf(EscrowException.class)
                .satisfies(e -> assertThat(((EscrowException) e).code()).isEqualTo("INVALID_ESCROW_STATE"));
    }

    @Test
    @DisplayName("가드: RELEASED된 홀드는 refund 불가 → INVALID_ESCROW_STATE (구매확정 후 환불 금지)")
    void refundAfterReleaseRejected() {
        EscrowHold h = heldHold();
        h.release(Instant.now());

        assertThatThrownBy(() -> h.refund(Instant.now()))
                .isInstanceOf(EscrowException.class)
                .satisfies(e -> assertThat(((EscrowException) e).code()).isEqualTo("INVALID_ESCROW_STATE"));
    }

    @Test
    @DisplayName("가드: REFUNDED된 홀드는 release 불가 → INVALID_ESCROW_STATE")
    void releaseAfterRefundRejected() {
        EscrowHold h = heldHold();
        h.refund(Instant.now());

        assertThatThrownBy(() -> h.release(Instant.now()))
                .isInstanceOf(EscrowException.class)
                .satisfies(e -> assertThat(((EscrowException) e).code()).isEqualTo("INVALID_ESCROW_STATE"));
    }
}
