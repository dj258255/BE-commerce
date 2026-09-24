package com.beomsu.becommerce.order.catalog.search;

import java.util.ArrayList;
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
        return Map.of("from", from, "size", size, "_source", false, "track_total_hits", true, "query", textQuery(query),
                "sort", RELEVANCE_THEN_ID);
    }

    /**
     * 점수 내림차순, 같으면 상품 id 오름차순(#262). 동점을 엔진 내부 문서 순서에 맡기면 색인 절차(세그먼트가 언제 만들어지고 어떻게
     * 병합됐는가)에 따라 순서가 바뀐다. ES 와 OpenSearch 의 상위 10개 겹침 0.8 이 전부 이것이었다(점수가 다른 쿼리 0).
     */
    static final List<Map<String, Object>> RELEVANCE_THEN_ID =
            List.of(Map.of("_score", "desc"), Map.of("product_id", "asc"));

    /** 정확 일치 절(3배)과 오타 허용 절을 bool should 로 묶은 텍스트 질의. */
    static Map<String, Object> textQuery(String query) {
        List<String> fields = FIELDS.entrySet().stream()
                .map(e -> e.getValue() == 1.0f ? e.getKey() : e.getKey() + "^" + e.getValue().intValue())
                .toList();
        Map<String, Object> exact = Map.of("multi_match", Map.of(
                "query", query, "fields", fields, "type", "best_fields", "boost", EXACT_BOOST));
        Map<String, Object> fuzzy = Map.of("multi_match", Map.of(
                "query", query, "fields", fields, "type", "best_fields", "fuzziness", "AUTO"));
        return Map.of("bool", Map.of("should", List.of(exact, fuzzy)));
    }

    /** 검색어 + 필터(#244). 필터는 점수에 끼지 않는 filter 절이다. */
    static Map<String, Object> filteredBody(String query, SearchFilters filters, int from, int size) {
        return Map.of("from", from, "size", size, "_source", false, "track_total_hits", true,
                "query", Map.of("bool", Map.of("must", List.of(textQuery(query)), "filter", filterClauses(filters))),
                "sort", RELEVANCE_THEN_ID);
    }

    /**
     * 패싯(#244). 검색어로 일치 집합을 잡고, 축마다 <b>자기 축을 뺀</b> 필터를 filter 집계로 건다 — DB 패싯과 같은 의미다.
     * 결과 행은 필요 없으므로 size 0.
     */
    static Map<String, Object> facetsBody(String query, SearchFilters filters) {
        Map<String, Object> colour = Map.of(
                "filter", Map.of("bool", Map.of("filter", filterClauses(filters.withoutColour()))),
                "aggs", Map.of("v", Map.of("terms", Map.of("field", "colour_code", "size", 100))));
        Map<String, Object> type = Map.of(
                "filter", Map.of("bool", Map.of("filter", filterClauses(filters.withoutProductType()))),
                "aggs", Map.of("v", Map.of("terms", Map.of("field", "product_type_kw", "size", 500))));
        return Map.of("size", 0, "track_total_hits", true, "query", textQuery(query),
                "aggs", Map.of("colour", colour, "type", type));
    }

    static List<Map<String, Object>> filterClauses(SearchFilters f) {
        List<Map<String, Object>> clauses = new ArrayList<>();
        term(clauses, "category_code", f.categoryCode());
        term(clauses, "subcategory_code", f.subcategoryCode());
        term(clauses, "colour_code", f.colourCode());
        term(clauses, "product_type_kw", f.productType());
        if (f.minPrice() != null || f.maxPrice() != null) {
            Map<String, Object> range = new LinkedHashMap<>();
            if (f.minPrice() != null) {
                range.put("gte", f.minPrice());
            }
            if (f.maxPrice() != null) {
                range.put("lte", f.maxPrice());
            }
            clauses.add(Map.of("range", Map.of("price", range)));
        }
        if (f.inStockOnly()) {
            clauses.add(Map.of("term", Map.of("in_stock", true)));
        }
        return clauses;
    }

    private static void term(List<Map<String, Object>> clauses, String field, String value) {
        if (value != null) {
            clauses.add(Map.of("term", Map.of(field, value)));
        }
    }

    /** ES 의 fuzziness AUTO 와 같은 규칙. 0~2자 0, 3~5자 1, 6자 이상 2. */
    static int autoEdits(String term) {
        int n = term.length();
        return n < 3 ? 0 : (n < 6 ? 1 : 2);
    }
}
