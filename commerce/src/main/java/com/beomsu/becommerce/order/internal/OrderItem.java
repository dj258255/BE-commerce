package com.beomsu.becommerce.order.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 주문 항목 — 주문 시점의 상품 정보를 <b>스냅샷</b>으로 고정한다.
 *
 * <p>상품명·단가를 주문 시점 값으로 복사해 두므로, 이후 상품 가격이 바뀌어도 이 주문의
 * 금액·정산·환불 기준은 불변이다(velog @roycewon의 ProductSnapshot, 배민 정산 스냅샷과 동일 원리).
 */
@Entity
@Table(name = "order_items")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;

    @Column(nullable = false)
    private long productId;

    /** ★ 스냅샷: 주문 시점 상품명 */
    @Column(nullable = false, length = 200)
    private String productName;

    /** ★ 스냅샷: 주문 시점 단가 */
    @Column(nullable = false)
    private long unitPrice;

    @Column(nullable = false)
    private int quantity;

    private OrderItem(long productId, String productName, long unitPrice, int quantity) {
        if (quantity <= 0) {
            throw new OrderException("INVALID_REQUEST", "수량은 1 이상이어야 합니다: " + quantity);
        }
        if (unitPrice < 0) {
            throw new OrderException("INVALID_REQUEST", "단가는 음수일 수 없습니다: " + unitPrice);
        }
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public static OrderItem of(long productId, String productName, long unitPrice, int quantity) {
        return new OrderItem(productId, productName, unitPrice, quantity);
    }

    /** 소유 주문을 귀속시킨다(양방향 연관관계 설정). Order 애그리거트 내부에서만 호출한다. */
    void belongTo(Order order) {
        this.order = order;
    }

    /** 이 항목의 소계(단가 × 수량). 오버플로 시 조용히 뒤집히지 않고 예외를 던진다. */
    public long subtotal() {
        try {
            return Math.multiplyExact(unitPrice, (long) quantity);
        } catch (ArithmeticException e) {
            throw new OrderException("AMOUNT_OVERFLOW", "주문 금액이 허용 범위를 초과했습니다.");
        }
    }
}
