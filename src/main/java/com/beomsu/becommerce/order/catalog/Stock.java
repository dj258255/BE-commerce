package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 상품 재고 — 동시성 실험용(Phase 5) 및 승인 시점 차감(ADR-003)의 대상.
 *
 * <p>{@code productId}를 직접 할당하는 PK로 쓴다(상품과 1:1). {@code @Version}으로 낙관적 락을
 * 걸어 동시 차감 경합을 감지한다. 조건부 차감({@link #deduct})으로 음수 재고를 도메인에서 차단한다.
 */
@Entity
@Table(name = "stock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Stock {

    @Id
    @Column(name = "product_id")
    private Long productId;

    @Column(nullable = false)
    private int quantity;

    @Version
    private long version;

    private Stock(Long productId, int quantity) {
        this.productId = productId;
        this.quantity = quantity;
    }

    public static Stock of(long productId, int quantity) {
        return new Stock(productId, quantity);
    }

    /**
     * 조건부 차감 — 재고가 요청 수량보다 적으면 예외(OUT_OF_STOCK), 아니면 차감한다.
     * DB 레벨에서는 {@code UPDATE ... WHERE quantity >= :n} 조건부 UPDATE로 강화된다(09 ERD).
     */
    public void deduct(int qty) {
        if (qty <= 0) {
            // 음수 차감은 재고를 오히려 늘린다 — 도메인에서 원천 차단한다.
            throw new OrderException("INVALID_REQUEST", "차감 수량은 1 이상이어야 합니다: " + qty);
        }
        if (quantity < qty) {
            throw OrderException.outOfStock(productId);
        }
        this.quantity -= qty;
    }
}
