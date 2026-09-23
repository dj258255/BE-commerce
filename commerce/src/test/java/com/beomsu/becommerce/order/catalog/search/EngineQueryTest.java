package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ES·OpenSearch·Lucene 이 같은 질의를 하는지는 이 정의 하나에 걸려 있다. 모양이 바뀌면 여기서 깨진다. */
class EngineQueryTest {

    @Test
    @DisplayName("요청 본문: 정확 일치(3배)와 오타 허용을 함께 건다 · 상품명과 종류에 가중치 2 · _source 없음")
    @SuppressWarnings("unchecked")
    void bodyShape() {
        Map<String, Object> body = EngineQuery.searchBody("dress", 20, 10);

        assertThat(body).containsEntry("from", 20).containsEntry("size", 10).containsEntry("_source", false);
        List<Map<String, Object>> should = (List<Map<String, Object>>)
                ((Map<String, Object>) ((Map<String, Object>) body.get("query")).get("bool")).get("should");
        Map<String, Object> exact = (Map<String, Object>) should.get(0).get("multi_match");
        Map<String, Object> fuzzy = (Map<String, Object>) should.get(1).get("multi_match");
        assertThat(exact).containsEntry("boost", EngineQuery.EXACT_BOOST).doesNotContainKey("fuzziness");
        assertThat(fuzzy).containsEntry("fuzziness", "AUTO").containsEntry("type", "best_fields");
        assertThat((List<String>) fuzzy.get("fields")).containsExactly("name^2", "product_type^2", "description");
    }

    @Test
    @DisplayName("오타 허용 폭은 ES 의 AUTO 와 같다: 2자 이하 0, 3~5자 1, 6자 이상 2")
    void autoEdits() {
        assertThat(EngineQuery.autoEdits("ab")).isZero();
        assertThat(EngineQuery.autoEdits("top")).isEqualTo(1);
        assertThat(EngineQuery.autoEdits("dress")).isEqualTo(1);
        assertThat(EngineQuery.autoEdits("blouse")).isEqualTo(2);
    }
}
