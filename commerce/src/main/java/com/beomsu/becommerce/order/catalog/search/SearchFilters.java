package com.beomsu.becommerce.order.catalog.search;

/**
 * 검색어와 함께 거는 필터(#244). 목록 화면의 필터와 같은 축이다.
 *
 * <p>패싯은 <b>자기 축을 뺀</b> 나머지 필터로 센다(DB 패싯과 같은 의미) — 색상을 하나 골라도 다른 색상의 개수가 보여야 한다.
 * 그래서 {@link #withoutColour()}·{@link #withoutProductType()} 을 둔다.
 */
public record SearchFilters(String categoryCode, String subcategoryCode, String colourCode, String productType,
                            Long minPrice, Long maxPrice, boolean inStockOnly) {

    public static final SearchFilters NONE = new SearchFilters(null, null, null, null, null, null, false);

    public boolean isEmpty() {
        return categoryCode == null && subcategoryCode == null && colourCode == null && productType == null
                && minPrice == null && maxPrice == null && !inStockOnly;
    }

    public SearchFilters withoutColour() {
        return new SearchFilters(categoryCode, subcategoryCode, null, productType, minPrice, maxPrice, inStockOnly);
    }

    public SearchFilters withoutProductType() {
        return new SearchFilters(categoryCode, subcategoryCode, colourCode, null, minPrice, maxPrice, inStockOnly);
    }

    /** 한 상품이 필터를 통과하는가. 후보 자르기와 테스트의 정답 계산이 같은 규칙을 쓴다. */
    public boolean matches(String category, String subcategory, String colour, String type, long price, boolean inStock) {
        return (categoryCode == null || categoryCode.equals(category))
                && (subcategoryCode == null || subcategoryCode.equals(subcategory))
                && (colourCode == null || colourCode.equals(colour))
                && (productType == null || productType.equals(type))
                && (minPrice == null || price >= minPrice)
                && (maxPrice == null || price <= maxPrice)
                && (!inStockOnly || inStock);
    }
}
