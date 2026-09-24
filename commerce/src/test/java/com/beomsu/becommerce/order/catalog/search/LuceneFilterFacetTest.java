package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 엔진 안 필터·패싯이 무차별 계산과 같은지(#244). 모든 문서가 "dress" 를 담아 텍스트 일치 집합 = 전체다. */
class LuceneFilterFacetTest {

    private final List<LuceneProductSearch.Doc> docs = new ArrayList<>();
    private LuceneProductSearch lucene;

    @BeforeEach
    void setUp() {
        String[] cats = {"ladieswear", "menswear", "kids"};
        String[] colours = {"black", "white", "red", "blue"};
        String[] types = {"Dress", "Skirt"};
        for (int i = 0; i < 240; i++) {
            docs.add(new LuceneProductSearch.Doc(i, "Soft dress " + i, types[i % 2], "A dress in cotton",
                    cats[i % 3], cats[i % 3] + ".sub" + (i % 2), colours[i % 4], 10_000L + i * 250L, i % 7 != 0));
        }
        lucene = new LuceneProductSearch(docs);
    }

    @AfterEach
    void tearDown() throws Exception {
        lucene.close();
    }

    private long truth(SearchFilters f) {
        return docs.stream().filter(d -> f.matches(d.categoryCode(), d.subcategoryCode(), d.colourCode(),
                d.productType(), d.price(), d.inStock())).count();
    }

    @Test
    @DisplayName("필터를 건 결과 수가 무차별 계산과 같다 — 가격 범위·대분류·색상·재고")
    void filteredTotalMatchesBruteForce() {
        SearchFilters f = new SearchFilters("ladieswear", null, "black", null, 20_000L, 60_000L, true);
        assertThat(lucene.searchFiltered("dress", f, 0, 10).total()).isEqualTo(truth(f));
        assertThat(truth(f)).isPositive();
    }

    @Test
    @DisplayName("패싯은 자기 축을 뺀 필터로 일치 집합 전체를 센다")
    void facetsExcludeOwnAxis() {
        SearchFilters f = new SearchFilters(null, null, "red", "Dress", null, 40_000L, false);

        SearchFacets facets = lucene.facets("dress", f);

        Map<String, Long> expectedColours = docs.stream()
                .filter(d -> f.withoutColour().matches(d.categoryCode(), d.subcategoryCode(), d.colourCode(), d.productType(), d.price(), d.inStock()))
                .collect(Collectors.groupingBy(LuceneProductSearch.Doc::colourCode, Collectors.counting()));
        Map<String, Long> expectedTypes = docs.stream()
                .filter(d -> f.withoutProductType().matches(d.categoryCode(), d.subcategoryCode(), d.colourCode(), d.productType(), d.price(), d.inStock()))
                .collect(Collectors.groupingBy(LuceneProductSearch.Doc::productType, Collectors.counting()));
        assertThat(facets.colours()).isEqualTo(expectedColours);
        assertThat(facets.productTypes()).isEqualTo(expectedTypes);
    }

    @Test
    @DisplayName("필터가 없으면 텍스트 검색과 같다")
    void noFilterIsPlainSearch() {
        assertThat(lucene.searchFiltered("dress", SearchFilters.NONE, 0, 10).total())
                .isEqualTo(lucene.search("dress", 0, 10).total());
    }
}
