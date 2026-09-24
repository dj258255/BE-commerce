package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 변경 반영(#246)의 계약.
 *
 * <ul>
 *   <li>갈아 끼운 문서는 검색기를 다시 연 <b>뒤에</b> 보인다 — 반영 지연의 하한이 이 주기다</li>
 *   <li>DB 에 없는 id 는 지운다 — 이벤트 내용이 아니라 지금 DB 가 답이다</li>
 *   <li><b>전체 재색인 중에 온 변경을 잃지 않는다</b> — 새 색인은 적재 시작 순간의 DB 라 그 뒤 변경이 없다</li>
 * </ul>
 */
class LuceneChangeTest {

    private static LuceneProductSearch.Doc doc(long id, String name, long price, boolean inStock) {
        return new LuceneProductSearch.Doc(id, name, "Dress", "cotton", "ladieswear", null, "black", price, inStock);
    }

    private static boolean found(ProductSearch s, String q, SearchFilters f, long id) {
        return s.searchFiltered(q, f, 0, 20).ids().contains(id);
    }

    @Test
    @DisplayName("갈아 끼운 문서는 검색기를 다시 연 뒤에 보이고, 지운 문서는 빠진다")
    void upsertVisibleAfterReaderRefresh() throws Exception {
        try (LuceneProductSearch lucene = new LuceneProductSearch(List.of(doc(1, "linen dress", 30_000, true)))) {
            lucene.upsert(doc(2, "quokka dress", 30_000, true));
            assertThat(found(lucene, "quokka", SearchFilters.NONE, 2)).isFalse();
            lucene.refreshReader();
            assertThat(found(lucene, "quokka", SearchFilters.NONE, 2)).isTrue();

            SearchFilters cheap = new SearchFilters(null, null, null, null, null, 10_000L, false);
            lucene.upsert(doc(1, "linen dress", 5_000, true));
            lucene.refreshReader();
            assertThat(found(lucene, "linen", cheap, 1)).isTrue();
            assertThat(lucene.docCount()).isEqualTo(2);        // 갈아 끼우기라 늘지 않는다

            lucene.delete(2);
            lucene.refreshReader();
            assertThat(found(lucene, "quokka", SearchFilters.NONE, 2)).isFalse();
        }
    }

    @Test
    @DisplayName("변경은 DB 를 다시 읽어 반영하고, DB 에 없는 id 는 지운다")
    void changedRereadsDatabase() {
        Map<Long, LuceneProductSearch.Doc> db = new ConcurrentHashMap<>(Map.of(1L, doc(1, "linen dress", 30_000, true)));
        RefreshingLuceneSearch search = new RefreshingLuceneSearch(() -> new ArrayList<>(db.values()), ids -> read(db, ids),
                Duration.ZERO, Duration.ZERO, new SimpleMeterRegistry());
        try {
            SearchFilters inStock = new SearchFilters(null, null, null, null, null, null, true);
            db.put(1L, doc(1, "linen dress", 30_000, false));   // 품절
            search.changed(List.of(1L));
            search.refreshReader();
            assertThat(found(search, "linen", inStock, 1)).isFalse();

            db.remove(1L);
            search.changed(List.of(1L));
            search.refreshReader();
            assertThat(search.docCount()).isZero();
        } finally {
            search.close();
        }
    }

    @Test
    @DisplayName("전체 재색인 중에 온 변경은 갈아 끼운 새 색인에도 들어간다")
    void changeDuringRebuildSurvivesSwap() throws Exception {
        Map<Long, LuceneProductSearch.Doc> db = new ConcurrentHashMap<>(Map.of(1L, doc(1, "linen dress", 30_000, true)));
        CountDownLatch loading = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        boolean[] first = {true};
        RefreshingLuceneSearch search = new RefreshingLuceneSearch(() -> {
            List<LuceneProductSearch.Doc> snapshot = new ArrayList<>(db.values());   // 적재 시작 순간의 DB
            if (!first[0]) {
                loading.countDown();
                await(release);                                                  // 적재가 끝나기 전에 변경이 온다
            }
            first[0] = false;
            return snapshot;
        }, ids -> read(db, ids), Duration.ZERO, Duration.ZERO, new SimpleMeterRegistry());
        try {
            Thread rebuild = new Thread(search::refresh);
            rebuild.start();
            assertThat(loading.await(5, TimeUnit.SECONDS)).isTrue();

            db.put(2L, doc(2, "quokka dress", 30_000, true));                    // 스냅숏 뒤의 변경
            search.changed(List.of(2L));
            release.countDown();
            rebuild.join(5_000);

            assertThat(found(search, "quokka", SearchFilters.NONE, 2)).isTrue();
        } finally {
            search.close();
        }
    }

    private static List<LuceneProductSearch.Doc> read(Map<Long, LuceneProductSearch.Doc> db, Collection<Long> ids) {
        return ids.stream().filter(db::containsKey).map(db::get).toList();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
