package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FallbackProductSearchTest {

    private static ProductSearch fixed(String name, List<Long> ids) {
        return new ProductSearch() {
            public SearchPage search(String query, int page, int size) {
                return new SearchPage(ids, ids.size());
            }
            public String engine() { return name; }
            public boolean ranksByRelevance() { return true; }
        };
    }

    private static ProductSearch broken() {
        return new ProductSearch() {
            public SearchPage search(String query, int page, int size) {
                throw new IllegalStateException("엔진이 죽었다");
            }
            public String engine() { return "opensearch"; }
            public boolean ranksByRelevance() { return true; }
        };
    }

    @Test
    @DisplayName("엔진이 실패하면 DB 검색이 답하고, 물러선 횟수가 지표에 남는다")
    void fallsBackAndCounts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FallbackProductSearch search = new FallbackProductSearch(broken(), fixed("like-fields", List.of(7L)), registry);

        FallbackProductSearch.Answer answer = search.answer("dress", 0, 10);

        assertThat(answer.page().ids()).containsExactly(7L);
        assertThat(answer.answeredBy().engine()).isEqualTo("like-fields");
        assertThat(registry.get("catalog.search.fallback").tag("engine", "opensearch").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("엔진이 답하면 물러서지 않는다")
    void primaryAnswers() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        FallbackProductSearch search = new FallbackProductSearch(fixed("opensearch", List.of(1L, 2L)),
                fixed("like-fields", List.of(7L)), registry);

        assertThat(search.search("dress", 0, 10).ids()).containsExactly(1L, 2L);
        assertThat(registry.get("catalog.search.fallback").counter().count()).isZero();
    }
}
