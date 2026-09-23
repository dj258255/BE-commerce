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

    record SearchPage(List<Long> ids, long total) {
        public static SearchPage empty() {
            return new SearchPage(List.of(), 0);
        }
    }
}
