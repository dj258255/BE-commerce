package com.beomsu.becommerce.point.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 포인트 이력 — append-only. 절대 UPDATE/DELETE 하지 않는다.
 *
 * <p>(orderNo, type) 조합으로 같은 주문의 같은 유형 처리가 이미 있었는지 조회해 멱등성을 보장한다
 * (예: 같은 주문의 USE가 두 번 요청돼도 한 번만 차감). 잔액은 계정에 두고, 여기에는 변화의 사실만 남긴다.
 */
@Entity
@Table(name = "point_histories")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PointHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private PointHistoryType type;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Instant createdAt;

    private PointHistory(long userId, PointHistoryType type, long amount, String orderNo) {
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.orderNo = orderNo;
        this.createdAt = Instant.now();
    }

    public static PointHistory of(long userId, PointHistoryType type, long amount, String orderNo) {
        return new PointHistory(userId, type, amount, orderNo);
    }
}
