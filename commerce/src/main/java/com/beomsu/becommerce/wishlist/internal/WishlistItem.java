package com.beomsu.becommerce.wishlist.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 찜한 상품 한 건 — 사용자 × 상품.
 *
 * <p>한 쌍은 하나뿐이다. {@code uk_wishlist_user_product} 유니크가 <b>멱등 계약 그 자체</b>이고,
 * 동시에 "내 찜 목록" 조회의 인덱스다(선두 컬럼이 {@code userId}). 별도 조회 인덱스를 두지 않는다.
 *
 * <p>{@code productId}에 FK 제약을 걸지 않는다 — ERD §10 규칙(논리적 FK + 인덱스). 상품이 사라져도
 * 찜 행은 남기고 조회 시 {@code available=false}로 알린다. 지우면 개인화 신호의 이력이 사라지고,
 * 없는 것을 있는 척하면 화면이 거짓말을 한다.
 */
@Entity
@Table(
        name = "wishlist_items",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_wishlist_user_product",
                columnNames = {"userId", "productId"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WishlistItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 인증 principal에서 얻은 값. 클라이언트가 보낸 userId는 절대 쓰지 않는다(스푸핑 방지). */
    @Column(nullable = false)
    private long userId;

    @Column(nullable = false)
    private long productId;

    @Column(nullable = false)
    private Instant createdAt;

    private WishlistItem(long userId, long productId) {
        this.userId = userId;
        this.productId = productId;
        this.createdAt = Instant.now();
    }

    public static WishlistItem of(long userId, long productId) {
        return new WishlistItem(userId, productId);
    }
}
