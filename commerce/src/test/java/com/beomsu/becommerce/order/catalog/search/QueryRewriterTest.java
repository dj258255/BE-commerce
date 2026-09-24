package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 검색어 고치기(#260). 라벨 사전과 미국식 목록이 쿼리를 어떻게 바꾸는지 고정한다. */
class QueryRewriterTest {

    private final QueryRewriter rewriter = new QueryRewriter(
            Map.of("블랙", "black", "라일락 퍼플", "lilac-purple"),
            Map.of("스커트", Set.of("skirts"), "저지 베이직", Set.of("jersey basic"), "양말·타이츠", Set.of("socks and tights"),
                    "Dressed", Set.of("dressed")),
            QueryRewriter.defaultSynonyms());

    @Test
    @DisplayName("한국어 색상은 필터가, 중분류는 원문 이름이 된다 — 띄어 써도 붙여 써도 같다")
    void koreanLabelsBecomeFilterAndEnglish() {
        QueryRewriter.Rewritten spaced = rewriter.rewrite("블랙 스커트");
        QueryRewriter.Rewritten joined = rewriter.rewrite("블랙스커트");

        assertThat(spaced.colourCode()).isEqualTo("black");
        assertThat(spaced.text()).isEqualTo("skirts");
        assertThat(joined).isEqualTo(spaced);
        assertThat(rewriter.rewrite("라일락퍼플 저지베이직").colourCode()).isEqualTo("lilac-purple");
        assertThat(rewriter.rewrite("저지 베이직").text()).isEqualTo("jersey basic");
        assertThat(rewriter.rewrite("양말·타이츠").text()).isEqualTo("socks and tights");   // 라벨의 가운뎃점
        assertThat(rewriter.rewrite("양말 타이츠").text()).isEqualTo("socks and tights");
        assertThat(rewriter.rewrite("Dressed").changed()).isFalse();                     // 한글 없는 라벨은 사전 밖
    }

    @Test
    @DisplayName("모르는 한글은 버리고, 영어 조각은 둔다")
    void unknownHangulDropped() {
        QueryRewriter.Rewritten r = rewriter.rewrite("스커트를 linen");
        assertThat(r.text()).isEqualTo("linen skirts");
        assertThat(rewriter.rewrite("원피스").text()).isEmpty();
    }

    @Test
    @DisplayName("미국식에는 영국식을 더하고 원래 말은 둔다. 해당 없는 영어 검색어는 그대로다")
    void usSynonymsAppendUk() {
        assertThat(rewriter.rewrite("pants").text()).isEqualTo("pants trousers");
        assertThat(rewriter.rewrite("black tank top").text()).isEqualTo("black tank top vest top");
        assertThat(rewriter.rewrite("tankini").text()).isEqualTo("tankini");          // 단어 경계
        QueryRewriter.Rewritten plain = rewriter.rewrite("linen dress");
        assertThat(plain.text()).isEqualTo("linen dress");
        assertThat(plain.changed()).isFalse();
    }
}
