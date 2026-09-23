package com.beomsu.becommerce.wallet.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 월렛 거래 이력 — append-only. 절대 UPDATE/DELETE 하지 않는다.
 *
 * <p>복식부기 발상: 잔액({@link WalletAccount#getBalance})을 덮어쓰는 것이 진실이 아니라,
 * <b>모든 자금 이동의 사실(충전/사용/환불)을 여기에 남기고 잔액은 그 이력의 파생값(materialized/스냅샷 캐시)</b>
 * 으로 본다. {@code balanceAfter}에 각 거래 직후 잔액을 함께 기록해 임의 시점 잔액을 감사·재구성할 수 있다.
 */
@Entity
@Table(name = "wallet_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private WalletTransactionType type;

    @Column(nullable = false)
    private long amount;

    /** 이 거래 직후의 잔액 — 파생 잔액의 스냅샷이자 감사 추적점. */
    @Column(nullable = false)
    private long balanceAfter;

    /**
     * 결제 차감(USE)·환불(REFUND)을 주문 단위로 멱등화하는 키. 충전(CHARGE)은 주문과 무관해 null이다.
     * (order_no, type)에 유니크 인덱스가 걸려 있어 같은 주문의 차감/환불은 1건만 남는다(사가 재진입 대비).
     */
    @Column(length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Instant createdAt;

    private WalletTransaction(long userId, WalletTransactionType type, long amount, long balanceAfter, String orderNo) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.orderNo = orderNo;
        this.createdAt = Instant.now();
    }

    public static WalletTransaction of(long userId, WalletTransactionType type, long amount, long balanceAfter, String orderNo) {
        return new WalletTransaction(userId, type, amount, balanceAfter, orderNo);
    }
}
