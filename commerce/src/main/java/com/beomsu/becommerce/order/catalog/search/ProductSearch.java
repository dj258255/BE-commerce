package com.beomsu.becommerce.order.catalog.search;

import java.util.List;

/**
 * 검색어로 상품 id 를 찾는 포트(#236, ADR-051).
 *
 * <p><b>id 와 순서만 돌려준다.</b> 가격·재고·이름은 호출자가 DB 에서 채운다. 그래서 엔진 색인이
 * 낡아도 화면에 나가는 가격·재고는 낡지 않는다 — 낡을 수 있는 것은 "무엇이 걸리는가"뿐이다.
 *
 * <p>순서가 곧 관련도다. 관련도를 모르는 구현(LIKE)은 신상품순을 돌려준다.
 */
public interface ProductSearch {

    /** 한 페이지. {@code page} 는 0부터. */
    SearchPage search(String query, int page, int size);

    /** 지표 태그와 로그에 쓰는 이름(설정값과 같다). */
    String engine();

    /** 관련도 순서를 주는가. 아니면 신상품순이다. */
    boolean ranksByRelevance();

    /**
     * 필터와 패싯을 <b>엔진 안에서</b> 처리하는가(#244). 아니면 호출자가 {@link CandidateFiltering} 으로 상위 후보를
     * 받아 DB 값으로 거르고 센다 — 그 방식은 조건이 까다로울 때 결과를 잃는다.
     */
    default boolean filtersInEngine() {
        return false;
    }

    /** 검색어 + 필터로 한 페이지. {@link #filtersInEngine()} 이 true 인 구현만. */
    default SearchPage searchFiltered(String query, SearchFilters filters, int page, int size) {
        throw new UnsupportedOperationException(engine() + " 는 엔진 안 필터를 지원하지 않는다");
    }

    /** 검색어 + 필터의 패싯. 각 축은 자기 축을 뺀 필터로 센다. {@link #filtersInEngine()} 이 true 인 구현만. */
    default SearchFacets facets(String query, SearchFilters filters) {
        throw new UnsupportedOperationException(engine() + " 는 엔진 안 패싯을 지원하지 않는다");
    }

    record SearchPage(List<Long> ids, long total) {
        public static SearchPage empty() {
            return new SearchPage(List.of(), 0);
        }
    }
}
