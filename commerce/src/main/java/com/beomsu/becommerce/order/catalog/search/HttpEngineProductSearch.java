package com.beomsu.becommerce.order.catalog.search;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

/**
 * Elasticsearch·OpenSearch 검색. 둘은 {@code _search} 요청·응답 모양이 같아 한 구현으로 부른다.
 *
 * <p>색인은 앱이 만들지 않는다 — 카탈로그가 앱 밖 파이프라인에서 적재되므로 색인도 거기서 만든다
 * ({@code tools/search_index.py}). 앱은 읽기만 한다.
 *
 * <p>호출이 실패하면 예외를 던진다. 물러서는 판단은 {@link FallbackProductSearch} 가 한다.
 */
public class HttpEngineProductSearch implements ProductSearch {

    private final RestClient client;
    private final String index;
    private final String engine;

    public HttpEngineProductSearch(RestClient client, String index, String engine) {
        this.client = client;
        this.index = index;
        this.engine = engine;
    }

    @Override
    public SearchPage search(String query, int page, int size) {
        JsonNode body = client.post()
                .uri("/{index}/_search", index)
                .contentType(MediaType.APPLICATION_JSON)
                .body(EngineQuery.searchBody(query, page * size, size))
                .retrieve()
                .body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException(engine + " 응답 본문이 비었다");
        }
        List<Long> ids = new ArrayList<>();
        for (JsonNode hit : body.path("hits").path("hits")) {
            ids.add(Long.parseLong(hit.path("_id").asText()));
        }
        return new SearchPage(ids, body.path("hits").path("total").path("value").asLong());
    }

    @Override
    public String engine() {
        return engine;
    }

    @Override
    public boolean ranksByRelevance() {
        return true;
    }
}
