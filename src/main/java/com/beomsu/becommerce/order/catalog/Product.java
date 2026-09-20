package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.order.internal.OrderException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 상품 카탈로그 — <b>가격의 서버 측 원천(source of truth)</b>.
 *
 * <p>가격은 절대 클라이언트에서 받지 않는다. 클라이언트가 보낸 가격으로 주문 금액을 계산하면,
 * 금액 위변조 검증({@link Order#verifyAmount})의 기준값 자체가 조작 가능해져 검증이 무의미해진다.
 * 주문 생성 시 서버가 이 카탈로그에서 가격을 조회해 {@link OrderItem} 스냅샷을 만든다.
 *
 * <p>탐색용 속성({@code categoryCode}·{@code subcategoryCode}·{@code description}·{@code imageUrl}·
 * {@code brand})은 쇼핑몰 화면을 위해 더한 것이고, 가격·재고의 권위에는 관여하지 않는다. 쓰기 표면은
 * 없고 시드와 마이그레이션으로만 채운다 — 조회는 {@link CatalogQueryService}가 담당한다.
 */
@Entity
@Table(name = "products")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Product {

    /** 상품 식별자(외부에서 지정). 재고({@link Stock})와 같은 키 공간을 쓴다. */
    @Id
    private long productId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(nullable = false)
    private long price;

    /** 대분류(V53). */
    @Column(length = 40)
    private String categoryCode;

    /** 중분류 — 대분류 × 상품 종류 조합(V56). NULL이면 대분류만 지정된 상품이다. */
    @Column(length = 40)
    private String subcategoryCode;

    @Column(length = 1000)
    private String description;

    @Column(length = 500)
    private String imageUrl;

    @Column(length = 120)
    private String brand;

    @Column(nullable = false)
    private boolean featured;

    @Column(nullable = false)
    private Instant createdAt;

    private Product(long productId, String name, long price, String categoryCode, String subcategoryCode,
                    String description, String imageUrl, String brand, boolean featured, Instant createdAt) {
        if (price < 0) {
            throw new OrderException("INVALID_REQUEST", "상품 가격은 음수일 수 없습니다: " + price);
        }
        this.productId = productId;
        this.name = name;
        this.price = price;
        this.categoryCode = categoryCode;
        this.subcategoryCode = subcategoryCode;
        this.description = description;
        this.imageUrl = imageUrl;
        this.brand = brand;
        this.featured = featured;
        this.createdAt = createdAt;
    }

    /** 가격 검증 경로와 단위 테스트가 쓰는 최소 팩토리 — 탐색 속성은 비운다. */
    public static Product of(long productId, String name, long price) {
        return new Product(productId, name, price, null, null, null, null, null, false, null);
    }

    /** 카탈로그 시드·조회가 쓰는 팩토리 — 중분류는 비운다. */
    public static Product of(long productId, String name, long price, String categoryCode,
                             String description, String imageUrl, String brand,
                             boolean featured, Instant createdAt) {
        return new Product(productId, name, price, categoryCode, null, description, imageUrl, brand,
                featured, createdAt);
    }

    /** 중분류까지 지정하는 팩토리(V56). */
    public static Product of(long productId, String name, long price, String categoryCode,
                             String subcategoryCode, String description, String imageUrl, String brand,
                             boolean featured, Instant createdAt) {
        return new Product(productId, name, price, categoryCode, subcategoryCode, description, imageUrl,
                brand, featured, createdAt);
    }
}
