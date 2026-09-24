package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 동점 순서(#258). 이름이 같은 상품은 검색 점수가 같다. 검색어와 무관한 변경(가격)으로 문서를 갈아 끼워도
 * 쪽 순서가 그대로여야 쪽을 넘기는 사이 중복·누락이 생기지 않는다.
 */
class LuceneTieOrderTest {

    private static LuceneProductSearch.Doc doc(long id, long price) {
        return new LuceneProductSearch.Doc(id, "Linen dress", "Dress", "linen", "ladieswear", null, "black", price, true);
    }

    private static List<Long> pages(LuceneProductSearch lucene) {
        List<Long> all = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            all.addAll(lucene.search("linen", p, 2).ids());
        }
        return all;
    }

    @Test
    @DisplayName("점수가 같으면 상품 id 순이고, 가격만 바꿔 갈아 끼워도 순서가 그대로다")
    void tiesStayPutAcrossUpdates() throws Exception {
        List<LuceneProductSearch.Doc> docs = List.of(doc(50, 1), doc(10, 1), doc(40, 1), doc(20, 1), doc(30, 1), doc(60, 1));
        try (LuceneProductSearch lucene = new LuceneProductSearch(docs)) {
            assertThat(pages(lucene)).containsExactly(10L, 20L, 30L, 40L, 50L, 60L);

            lucene.upsert(doc(20, 99));      // 검색어와 무관한 변경
            lucene.upsert(doc(10, 77));
            lucene.refreshReader();

            assertThat(pages(lucene)).containsExactly(10L, 20L, 30L, 40L, 50L, 60L);
        }
    }

    /**
     * 동점 정렬로 막지 못하는 것(#258 에서 남은 누락 9건의 원인). 갈아 끼운 문서의 옛 판은 병합 전까지 단어 통계에 남는다.
     * 그래서 가격만 바꿔도 그 단어의 IDF 가 내려가, <b>다른 단어로 점수를 얻는 상품과의 순서</b>가 뒤집힐 수 있다.
     * 이 테스트는 그 성질을 고정해 둔다 — 루씬이 이 동작을 바꾸면 여기서 알게 된다.
     */
    @Test
    @DisplayName("한계: 갈아 끼운 문서의 옛 판이 통계에 남아, 다른 단어로 맞는 상품과의 순서가 뒤집힌다")
    void updatesShiftScoresAcrossTermsUntilMerge() throws Exception {
        List<LuceneProductSearch.Doc> docs = new ArrayList<>();
        docs.add(new LuceneProductSearch.Doc(1, "alpha", "", "", "ladieswear", null, "black", 1, true));
        docs.add(new LuceneProductSearch.Doc(2, "beta", "", "", "ladieswear", null, "black", 1, true));
        for (long i = 0; i < 5; i++) {
            docs.add(new LuceneProductSearch.Doc(100 + i, "alpha filler", "", "", "ladieswear", null, "black", 1, true));
            docs.add(new LuceneProductSearch.Doc(200 + i, "beta filler", "", "", "ladieswear", null, "black", 1, true));
        }
        try (LuceneProductSearch lucene = new LuceneProductSearch(docs)) {
            List<Long> before = lucene.search("alpha beta", 0, 20).ids();
            assertThat(before.indexOf(1L)).isLessThan(before.indexOf(2L));              // 동점 → id 순

            for (long i = 0; i < 3; i++) {                                              // alpha 쪽 문서의 가격만 바꾼다
                lucene.upsert(new LuceneProductSearch.Doc(100 + i, "alpha filler", "", "", "ladieswear", null, "black", 9, true));
            }
            lucene.refreshReader();

            List<Long> after = lucene.search("alpha beta", 0, 20).ids();
            assertThat(after.indexOf(2L)).isLessThan(after.indexOf(1L));                // alpha 의 IDF 가 내려갔다
            assertThat(after).hasSameSizeAs(before);                                     // 걸리는 상품은 그대로다
        }
    }
}

