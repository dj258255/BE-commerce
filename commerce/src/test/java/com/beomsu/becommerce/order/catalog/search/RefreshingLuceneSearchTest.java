package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RefreshingLuceneSearchTest {

    @Test
    @DisplayName("카탈로그에 새 상품이 들어오면 다음 갱신 뒤에 검색된다")
    void refreshPicksUpNewProducts() {
        List<LuceneProductSearch.Doc> catalog = new ArrayList<>(List.of(
                new LuceneProductSearch.Doc(1, "Darling blouse", "Blouse", "Blouse in silk")));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (RefreshingLuceneSearch search = new RefreshingLuceneSearch(() -> List.copyOf(catalog), Duration.ZERO, registry)) {
            assertThat(search.search("cardigan", 0, 10).ids()).isEmpty();

            catalog.add(new LuceneProductSearch.Doc(2, "Soft cardigan", "Cardigan", "Cardigan in fine knit"));
            search.refresh();

            assertThat(search.search("cardigan", 0, 10).ids()).containsExactly(2L);
            assertThat(registry.get("catalog.search.lucene.refresh").tag("result", "ok").counter().count()).isEqualTo(1.0);
        }
    }

    @Test
    @DisplayName("갱신이 실패하면 옛 색인을 계속 쓰고 실패를 센다 — 검색이 비지 않는다")
    void failedRefreshKeepsOldIndex() {
        AtomicBoolean broken = new AtomicBoolean(false);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        try (RefreshingLuceneSearch search = new RefreshingLuceneSearch(() -> {
            if (broken.get()) {
                throw new IllegalStateException("DB 가 죽었다");
            }
            return List.of(new LuceneProductSearch.Doc(1, "Darling blouse", "Blouse", "Blouse in silk"));
        }, Duration.ZERO, registry)) {
            broken.set(true);
            search.refresh();

            assertThat(search.search("blouse", 0, 10).ids()).containsExactly(1L);
            assertThat(registry.get("catalog.search.lucene.refresh").tag("result", "failed").counter().count()).isEqualTo(1.0);
        }
    }
}
