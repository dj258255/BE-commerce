package com.beomsu.becommerce.order.catalog;

/**
 * 카테고리 조회 뷰 — {@code GET /api/v1/categories}의 각 항목.
 *
 * <p>{@code productCount}는 화면이 바로 쓸 수 있게 실어 보낸다. 대분류이면 **중분류 합계**이고,
 * 중분류면 자기 상품 수다. {@code parentCode}가 NULL이면 대분류다 — 화면은 이 필드로 트리를 만든다.
 */
public record CategoryView(String code, String name, String description, int sortOrder,
                           long productCount, String parentCode) {

    static CategoryView of(Category category, long productCount) {
        return new CategoryView(category.getCode(), category.getName(), category.getDescription(),
                category.getSortOrder(), productCount, category.getParentCode());
    }
}
