package com.beomsu.becommerce.escrow.internal;

import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.order.internal.Order;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 에스크로 홀드 애그리거트 — 주문당 하나의 보류 금액과 그 생명주기(HELD→RELEASED/REFUNDED)를 소유한다.
 *
 * <p>결제 승인 시 {@link #hold}로 만들어지고, 구매확정 시 {@link #release}로 정산 가능 상태가 되며,
 * 취소 시 {@link #refund}로 판매자 미정산 상태가 된다. 상태 전이는 HELD에서만 출발할 수 있게 가드해
 * 이미 종결된 홀드가 다시 전이되는 것을 막는다({@code Order}·{@code Payment}와 동일한 가드 방식).
 * order_no 유니크로 주문당 1홀드를 DB 레벨에서도 보장한다.
 */
@Entity
@Table(name = "escrow_holds")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class EscrowHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 주문 번호(ULID). 주문당 1홀드 — 유니크. */
    @Column(nullable = false, length = 200)
    private String orderNo;

    /** 보류 금액(KRW). */
    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EscrowStatus status;

    /** 보류 시작 시각(결제 승인 시각). */
    @Column(nullable = false)
    private Instant heldAt;

    /** 자동 구매확정 예정 시각 — 이 시각이 지나도록 무응답이면 배치가 자동 릴리스한다. */
    @Column(nullable = false)
    private Instant autoReleaseAt;

    /** 종결 시각(RELEASED/REFUNDED로 전이한 시각). HELD 동안은 null. */
    @Column
    private Instant resolvedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private EscrowHold(String orderNo, long amount, Instant heldAt, Instant autoReleaseAt) {
        Instant now = Instant.now();
        this.orderNo = orderNo;
        this.amount = amount;
        this.status = EscrowStatus.HELD;
        this.heldAt = heldAt;
        this.autoReleaseAt = autoReleaseAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 결제금 보류 — HELD 상태로 홀드를 생성한다. */
    public static EscrowHold hold(String orderNo, long amount, Instant heldAt, Instant autoReleaseAt) {
        return new EscrowHold(orderNo, amount, heldAt, autoReleaseAt);
    }

    /**
     * 구매확정 → RELEASED(정산 가능). HELD가 아니면 예외를 던진다 — 이미 종결된(환불/릴리스된) 홀드를
     * 다시 릴리스하는 것을 막는다. 멱등 판단(이미 RELEASED면 재발행 skip)은 서비스가 상태로 처리한다.
     */
    public void release(Instant now) {
        if (this.status != EscrowStatus.HELD) {
            throw EscrowException.invalidState(
                    "HELD 상태의 홀드만 릴리스할 수 있습니다: orderNo=%s, status=%s".formatted(orderNo, status));
        }
        this.status = EscrowStatus.RELEASED;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    /**
     * 취소 → REFUNDED(판매자 미정산). 구매확정(RELEASED) 전, 즉 HELD일 때만 환불로 전이한다.
     * 이미 종결된 홀드는 예외를 던지고, 멱등 skip은 서비스({@code refundIfHeld})가 상태로 판단한다.
     */
    public void refund(Instant now) {
        if (this.status != EscrowStatus.HELD) {
            throw EscrowException.invalidState(
                    "HELD 상태의 홀드만 환불할 수 있습니다: orderNo=%s, status=%s".formatted(orderNo, status));
        }
        this.status = EscrowStatus.REFUNDED;
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    /** 아직 보류 중(정산도 환불도 되지 않은)인지. */
    public boolean isHeld() {
        return this.status == EscrowStatus.HELD;
    }
}
