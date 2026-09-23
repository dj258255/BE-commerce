package com.beomsu.becommerce.fraud.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 카드 하나의 결제 한 건. <b>모델이 볼 창을 만드는 재료다.</b>
 *
 * <p>{@link TxnRecord} 와 나눠 둔 이유는 피처 추출을 순수 함수로 두려는 것이다. 저장 형식이
 * 바뀌어도 {@link SequenceFeatures} 는 안 바뀌고, 평가 하네스는 실 DB 없이 돈다.
 *
 * <p><b>주문 하나에 한 줄이다.</b> 아웃박스가 at-least-once 라 같은 결제 이벤트가 두 번 온다.
 * 두 번 들어오면 창의 건수가 부풀어 {@code windowCount} 와 {@code escalation} 이 통째로 틀어진다.
 * 유니크 제약이 마지막에 막고, 저장하는 쪽도 미리 본다.
 */
@Entity
@Table(name = "card_transactions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CardTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String cardKey;

    @Column(nullable = false)
    private String orderNo;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false)
    private int installmentMonths;

    /** 요청 시점 신호. 사후 탐지 경로에는 안 실려 와서 지금은 비어 있다. */
    private String deviceId;

    private String ip;

    @Column(nullable = false)
    private Instant occurredAt;

    @Column(nullable = false)
    private Instant createdAt;

    private CardTransaction(String cardKey, String orderNo, long amount, int installmentMonths,
                            String deviceId, String ip, Instant occurredAt) {
        this.cardKey = cardKey;
        this.orderNo = orderNo;
        this.amount = amount;
        this.installmentMonths = installmentMonths;
        this.deviceId = deviceId;
        this.ip = ip;
        this.occurredAt = occurredAt;
        this.createdAt = Instant.now();
    }

    public static CardTransaction of(String cardKey, String orderNo, long amount,
                                     int installmentMonths, String deviceId, String ip,
                                     Instant occurredAt) {
        return new CardTransaction(cardKey, orderNo, amount, installmentMonths, deviceId, ip, occurredAt);
    }

    /** 피처 추출이 쓰는 형태로 바꾼다. */
    public TxnRecord toRecord() {
        return new TxnRecord(amount, occurredAt, deviceId, ip, installmentMonths);
    }
}
