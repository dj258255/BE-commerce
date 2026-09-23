package com.beomsu.becommerce.order.catalog.search;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 외부 엔진(ES·OpenSearch)과 앱 안 Lucene 이 <b>같은 질의</b>를 하도록 한 곳에 모은 정의.
 *
 * <p>세 엔진의 품질을 비교하려면 필드·가중치·오타 허용이 같아야 한다. 다르면 엔진을 비교한 것이 아니라
 * 질의 설정을 비교한 것이 된다.
 */
final class EngineQuery {

    /** 필드와 가중치. 상품명·종류를 설명보다 2배 무겁게 본다 — 설명은 길어서 우연히 걸리는 말이 많다. */
    static final Map<String, Float> FIELDS = orderedFields();

    private EngineQuery() {
    }

    private static Map<String, Float> orderedFields() {
        Map<String, Float> fields = new LinkedHashMap<>();
        fields.put("name", 2.0f);
        fields.put("product_type", 2.0f);
        fields.put("description", 1.0f);
        return fields;
    }

    /** 정확 일치 절의 가중치. 퍼지 절보다 무겁게 둔다 — 아래 {@link #searchBody} 참고. */
    static final float EXACT_BOOST = 3.0f;

    /**
     * ES·OpenSearch 공통 요청 본문. <b>정확 일치 절과 오타 허용 절을 함께</b> 건다(bool should).
     *
     * <p>1차 실측(#236)에서 오타 허용 절만 걸었더니 {@code blazer} 가 {@code lazer}·{@code razer} 를 끌고 왔다.
     * 두 글자까지 틀려도 되는 6자 단어이고, 끌려온 말이 드물어 IDF 가 높으니 정확히 맞는 상품보다 위로 갔다.
     * 그래서 정확 일치를 {@value #EXACT_BOOST}배로 먼저 세우고, 오타 허용은 정확히 맞는 말이 없을 때 받쳐 주는
     * 자리로 내렸다. 필드는 best_fields(필드별 점수 중 최댓값), 오타 허용은 {@code AUTO}(3~5자 1글자, 6자 이상 2글자).
     */
    static Map<String, Object> searchBody(String query, int from, int size) {
        List<String> fields = FIELDS.entrySet().stream()
                .map(e -> e.getValue() == 1.0f ? e.getKey() : e.getKey() + "^" + e.getValue().intValue())
                .toList();
        Map<String, Object> exact = Map.of("multi_match", Map.of(
                "query", query, "fields", fields, "type", "best_fields", "boost", EXACT_BOOST));
        Map<String, Object> fuzzy = Map.of("multi_match", Map.of(
                "query", query, "fields", fields, "type", "best_fields", "fuzziness", "AUTO"));
        return Map.of(
                "from", from,
                "size", size,
                "_source", false,
                "track_total_hits", true,
                "query", Map.of("bool", Map.of("should", List.of(exact, fuzzy))));
    }

    /** ES 의 fuzziness AUTO 와 같은 규칙. 0~2자 0, 3~5자 1, 6자 이상 2. */
    static int autoEdits(String term) {
        int n = term.length();
        return n < 3 ? 0 : (n < 6 ? 1 : 2);
    }
}
