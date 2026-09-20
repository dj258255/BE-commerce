package com.beomsu.becommerce.order.catalog;

/** 상품 상세 뷰 — {@code GET /api/v1/products/{id}}. 요약에 설명을 더한다. */
public record ProductDetailView(long productId, String name, long price, String brand,
                                String categoryCode, String categoryName, String description,
                                String imageUrl, boolean inStock) {

    static ProductDetailView of(Product p, String categoryName, boolean inStock) {
        return new ProductDetailView(p.getProductId(), p.getName(), p.getPrice(), p.getBrand(),
                p.getCategoryCode(), categoryName, p.getDescription(), p.getImageUrl(), inStock);
    }
}
