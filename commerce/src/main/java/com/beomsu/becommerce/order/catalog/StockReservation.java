package com.beomsu.becommerce.order.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 재고 예약 한 줄(#374). 예약할 때 재고는 이미 조건부 UPDATE 로 빠져 있다. 이 행은 그 사실과 끝(확정 · 되돌림)을 남긴다.
 *
 * <p>상태 전이는 모두 조건부 UPDATE({@link StockReservationRepository})로 한다. 같은 주문의 확정 · 되돌림이
 * 여러 번 불려도(복구 배치가 확정 단계를 다시 도는 경우) 재고가 한 번만 움직인다.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StockReservation {

    public enum Status { RESERVED, CLAIMED, RELEASED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private StockReservation(String orderNo, long productId, int quantity, Instant now) {
        this.orderNo = orderNo;
        this.productId = productId;
        this.quantity = quantity;
        this.status = Status.RESERVED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static StockReservation reserve(String orderNo, long productId, int quantity, Instant now) {
        return new StockReservation(orderNo, productId, quantity, now);
    }
}
