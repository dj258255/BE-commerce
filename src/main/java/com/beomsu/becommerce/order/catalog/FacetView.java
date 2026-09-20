package com.beomsu.becommerce.order.catalog;

import java.util.List;

/**
 * 패싯 뷰 — {@code GET /api/v1/products/facets}. 목록 화면의 필터 패널이 쓰는 값·개수 묶음.
 *
 * <p>JSON 필드 이름은 {@code colours}·{@code productTypes}다. 각 패싯은 <b>자기 축을 뺀</b> 나머지
 * 필터만 적용해 센다 — 그래야 한 색을 고른 상태에서도 다른 색이 몇 개인지 볼 수 있다(패싯의 관례).
 */
public record FacetView(List<FacetCount> colours, List<FacetCount> productTypes) {
}
