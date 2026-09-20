package com.beomsu.becommerce.wishlist.internal;

import com.beomsu.becommerce.order.ProductCatalogFacts.ProductCardFacts;

import java.time.Instant;

/**
 * 찜 한 건의 응답 뷰 — 엔티티를 밖으로 내보내지 않는다.
 *
 * <p>{@code available}이 {@code false}면 <b>상품 행이 사라진 찜</b>이다(카탈로그가 은퇴시킨 상품 등).
 * 그때 {@code name}·{@code price}는 {@code null}이다. 없는 것을 있는 척하지도, 조용히 목록에서
 * 지우지도 않는다 — 지우면 사용자가 "내 찜이 왜 없어졌지"를 알 수 없다.
 */
public record WishlistView(long productId, String name, Long price, String brand, String imageUrl,
                           boolean inStock, boolean available, Instant addedAt) {

    /** {@code card}가 {@code null}이면 상품이 사라진 찜이다. */
    public static WishlistView of(long productId, ProductCardFacts card, Instant addedAt) {
        if (card == null) {
            return new WishlistView(productId, null, null, null, null, false, false, addedAt);
        }
        return new WishlistView(productId, card.name(), card.price(), card.brand(),
                card.imageUrl(), card.inStock(), true, addedAt);
    }
}
