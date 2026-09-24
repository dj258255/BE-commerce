package com.beomsu.becommerce.order.catalog.search;

import java.util.Map;

/** 검색 결과의 패싯 — 색상 코드별 · 상품 종류별 개수. 이름은 호출자가 붙인다. */
public record SearchFacets(Map<String, Long> colours, Map<String, Long> productTypes) {
}
