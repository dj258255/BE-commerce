package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 앱 안 Lucene 이 LIKE 가 못 찾는 두 경우(오타·어형 변화)를 찾는지 본다(#236).
 * 코퍼스는 실제 카탈로그 행 모양(내부 이름 + 종류 + 영어 설명)을 흉내 낸다.
 */
class LuceneProductSearchTest {

    private LuceneProductSearch lucene;

    @BeforeEach
    void setUp() {
        lucene = new LuceneProductSearch(List.of(
                new LuceneProductSearch.Doc(1, "Darling blouse", "Blouse", "Blouse in softly draping silk with a small frilled collar"),
                new LuceneProductSearch.Doc(2, "Cambell chino", "Trousers", "Chinos in washed stretch cotton with an adjustable waist"),
                new LuceneProductSearch.Doc(3, "LEGGINGS FANCY", "Leggings/Tights", "Leggings in washed, stretch denim with an elasticated waist"),
                new LuceneProductSearch.Doc(4, "OL Freddy", "Shorts", "Short, wide shorts in a viscose weave")));
    }

    @AfterEach
    void tearDown() throws Exception {
        lucene.close();
    }

    @Test
    @DisplayName("오타(인접 두 글자 교환)가 있어도 찾는다 — LIKE 는 0건이다")
    void typo() {
        assertThat(lucene.search("bluose", 0, 10).ids()).first().isEqualTo(1L);
    }

    @Test
    @DisplayName("단수·복수가 달라도 찾는다 — 어간 추출")
    void inflection() {
        assertThat(lucene.search("blouses", 0, 10).ids()).first().isEqualTo(1L);
        assertThat(lucene.search("chinos", 0, 10).ids()).first().isEqualTo(2L);
    }

    @Test
    @DisplayName("상품명에 없어도 종류·설명에 있으면 찾는다")
    void otherFields() {
        assertThat(lucene.search("trousers", 0, 10).ids()).containsExactly(2L);
    }

    @Test
    @DisplayName("정확히 맞는 말이 드물게 비슷한 말보다 앞선다 — blazer 가 lazer 에 밀리지 않는다(1차 실측의 결함)")
    void exactBeatsRareNeighbour() {
        try (LuceneProductSearch s = new LuceneProductSearch(List.of(
                new LuceneProductSearch.Doc(10, "Lazer Razer brief", "Swimwear bottom", "Fully lined bikini bottoms"),
                new LuceneProductSearch.Doc(11, "Manson slim fit", "Blazer", "Single-breasted jacket"),
                new LuceneProductSearch.Doc(12, "Relaxed fit", "Blazer", "Blazer in woven fabric"),
                new LuceneProductSearch.Doc(13, "Other", "Blazer", "Blazer with notch lapels")))) {
            assertThat(s.search("blazer", 0, 3).ids()).containsExactlyInAnyOrder(11L, 12L, 13L);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("불용어만 남는 검색어는 빈 결과다 — 예외를 내지 않는다")
    void stopwordsOnly() {
        assertThat(lucene.search("the a", 0, 10).ids()).isEmpty();
    }

    @Test
    @DisplayName("둘째 페이지는 첫 페이지 다음부터 이어진다")
    void paging() {
        List<Long> first = lucene.search("stretch", 0, 1).ids();
        List<Long> second = lucene.search("stretch", 1, 1).ids();
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1).doesNotContainAnyElementsOf(first);
        assertThat(lucene.search("stretch", 0, 1).total()).isEqualTo(2);
    }
}
