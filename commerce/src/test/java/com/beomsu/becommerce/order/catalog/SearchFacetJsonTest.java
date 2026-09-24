package com.beomsu.becommerce.order.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 패싯과 DB 패싯은 <b>같은 JSON 모양</b>이어야 한다 — 화면은 {@code /products/facets} 하나만 읽는다.
 *
 * <p>처음 구현은 레코드 컴포넌트를 {@code getCode} 로 지어 응답 키가 {@code getCode} 가 됐다. 서비스 단위
 * 테스트는 객체만 봐서 통과했고, API 로 재는 실측 하네스가 처음 잡았다(#244).
 */
class SearchFacetJsonTest {

    @Test
    @DisplayName("검색 패싯 한 값은 code·name·count 세 키로만 나간다")
    void searchFacetSerializesLikeDbFacet() throws Exception {
        FacetView view = new FacetView(
                List.of(new CatalogQueryService.SearchFacetCount("black", "블랙", 3L)), List.of());

        JsonNode colour = new ObjectMapper().valueToTree(view).get("colours").get(0);

        assertThat(colour.fieldNames()).toIterable().containsExactlyInAnyOrder("code", "name", "count");
        assertThat(colour.get("code").asText()).isEqualTo("black");
        assertThat(colour.get("count").asLong()).isEqualTo(3L);
    }
}
