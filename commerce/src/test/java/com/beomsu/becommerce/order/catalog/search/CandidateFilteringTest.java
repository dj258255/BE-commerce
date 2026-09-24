package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.order.catalog.Product;
import com.beomsu.becommerce.order.catalog.ProductRepository;
import com.beomsu.becommerce.order.catalog.StockRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 후보 자르기의 대가를 고정한다 — 조건에 맞는 상품이 관련도 500위 밖이면 보이지 않는다(#244). */
class CandidateFilteringTest {

    @Test
    @DisplayName("조건에 맞는 상품이 상위 500개 밖에 있으면 결과에서 빠진다")
    void losesMatchesBeyondCandidateCap() {
        // 엔진은 1,000개를 찾는다. 대분류 kids 는 501위부터 뒤에만 있다
        List<Long> ranked = new ArrayList<>();
        for (long id = 1; id <= 1000; id++) {
            ranked.add(id);
        }
        ProductSearch text = new ProductSearch() {
            public SearchPage search(String query, int page, int size) {
                int from = Math.min(page * size, ranked.size());
                return new SearchPage(ranked.subList(from, Math.min(from + size, ranked.size())), ranked.size());
            }
            public String engine() { return "test"; }
            public boolean ranksByRelevance() { return true; }
        };
        ProductRepository products = mock(ProductRepository.class);
        when(products.findAllById(anyIterable())).thenAnswer(inv -> {
            List<Product> out = new ArrayList<>();
            for (Object o : (Iterable<?>) inv.getArgument(0)) {
                long id = (Long) o;
                out.add(Product.of(id, "상품" + id, 10_000L, id > 500 ? "kids" : "ladieswear", "설명", "img", "브랜드",
                        false, Instant.parse("2026-09-01T00:00:00Z")));
            }
            return out;
        });
        StockRepository stock = mock(StockRepository.class);
        when(stock.findByProductIdIn(anyCollection())).thenReturn(List.of());

        SearchFilters kids = new SearchFilters("kids", null, null, null, null, null, false);
        ProductSearch.SearchPage page = new CandidateFiltering(products, stock).filter(text, "dress", kids, 0, 20);

        assertThat(page.total()).isZero();   // 정답은 500개다
    }
}
