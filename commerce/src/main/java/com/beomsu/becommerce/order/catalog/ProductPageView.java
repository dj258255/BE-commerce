package com.beomsu.becommerce.order.catalog;

import java.util.List;

/**
 * 상품 목록 페이지 뷰 — 페이지네이션 메타를 함께 싣는다.
 *
 * <p>Spring의 {@code Page}를 그대로 직렬화하면 내부 구조가 노출되고 버전마다 모양이 바뀐다.
 * 화면이 쓰는 값만 골라 고정한다.
 */
public record ProductPageView(List<ProductSummaryView> items, int page, int size,
                              long totalElements, int totalPages) {

    static ProductPageView of(List<ProductSummaryView> items, int page, int size,
                              long totalElements, int totalPages) {
        return new ProductPageView(items, page, size, totalElements, totalPages);
    }
}
