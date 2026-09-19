package com.beomsu.pay.order.catalog;

/** 카테고리 조회 뷰 — {@code GET /api/v1/categories}의 각 항목. 상품 수를 함께 실어 화면이 바로 쓴다. */
public record CategoryView(String code, String name, String description, int sortOrder, long productCount) {

    static CategoryView of(Category category, long productCount) {
        return new CategoryView(category.getCode(), category.getName(), category.getDescription(),
                category.getSortOrder(), productCount);
    }
}
