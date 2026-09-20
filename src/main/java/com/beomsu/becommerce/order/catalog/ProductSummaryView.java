package com.beomsu.becommerce.order.catalog;

/**
 * 상품 요약 뷰 — 목록·검색·홈의 각 카드.
 *
 * <p>엔티티를 그대로 노출하지 않는 읽기 전용 record다. {@code inStock}은 재고 0을 화면이 알아채
 * 장바구니 버튼을 막게 하려고 싣는다 — 재고 차감은 승인 시점이라 여기서 막지 않으면 주문 생성까지는
 * 통과하고 승인에서야 실패한다.
 */
public record ProductSummaryView(long productId, String name, long price, String brand,
                                 String categoryCode, String categoryName, String imageUrl,
                                 boolean inStock) {

    static ProductSummaryView of(Product p, String categoryName, boolean inStock) {
        return new ProductSummaryView(p.getProductId(), p.getName(), p.getPrice(), p.getBrand(),
                p.getCategoryCode(), categoryName, p.getImageUrl(), inStock);
    }
}
