package com.beomsu.becommerce.order.catalog.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 상품·재고 변경(CDC)을 받아 ES·OpenSearch 색인에 반영한다(#246).
 *
 * <p>색인이 하나라 <b>색인기도 하나면 된다</b>. 인스턴스가 여럿이어도 같은 그룹이라 변경마다 한 인스턴스만 받는다.
 * Lucene 과 다른 점이 이것이다({@link LuceneChangeListener} 는 인스턴스마다 전부 받는다).
 *
 * <p>검색에 보이는 것은 엔진의 다음 refresh(매핑 기본 1초) 뒤다. {@code refresh=wait_for} 를 걸지 않는다. 걸면 색인기가
 * refresh 를 기다리느라 변경이 몰릴 때 뒤가 밀린다.
 *
 * <p><b>실패를 둘로 나눈다</b>(#246 실측에서 드러났다).
 * <ul>
 *   <li><b>문서 하나가 거절됨(4xx, 429 제외)</b>: 다시 보내도 같은 답이다. 세고({@code catalog.search.indexer.rejected})
 *       넘긴다. 던지면 배치 전체가 재시도에 묶여 같은 배치의 다른 변경까지 늦는다</li>
 *   <li><b>엔진이 못 받음(연결 실패, 5xx, 429)</b>: 던져서 컨테이너가 배치를 다시 시도하게 한다. 삼키면 색인이 조용히 낡는다</li>
 * </ul>
 * 본문은 UTF-8 바이트로 보낸다. 문자열로 주면 {@code application/x-ndjson} 에 문자셋이 없어 기본 문자셋으로 인코딩되고,
 * 설명에 비 ASCII 문자가 있는 상품이 ES 에서 JSON 파싱 오류로 거절됐다.
 */
@Component
@Profile("kafka")
@ConditionalOnExpression("${app.catalog.search.cdc.enabled:false} and ('${app.catalog.search.engine:lucene}' == 'elasticsearch'"
        + " or '${app.catalog.search.engine:lucene}' == 'opensearch')")
class EngineIndexer {

    private static final Logger log = LoggerFactory.getLogger(EngineIndexer.class);
    static final MediaType NDJSON = new MediaType("application", "x-ndjson", StandardCharsets.UTF_8);

    private final CatalogDocs docs;
    private final RestClient client;
    private final String index;
    private final ObjectMapper json = new ObjectMapper();
    private final Counter rejected;

    @Autowired
    EngineIndexer(CatalogDocs docs, MeterRegistry registry,
                  @Value("${app.catalog.search.engine-url:http://localhost:9200}") String engineUrl,
                  @Value("${app.catalog.search.index:products}") String index) {
        this(docs, registry, RestClient.builder().baseUrl(engineUrl), index);
    }

    EngineIndexer(CatalogDocs docs, MeterRegistry registry, RestClient.Builder client, String index) {
        this.docs = docs;
        this.client = client.build();
        this.index = index;
        this.rejected = Counter.builder("catalog.search.indexer.rejected").register(registry);
    }

    @KafkaListener(topics = "${app.catalog.search.cdc.topic:catalog.change}",
            groupId = "catalog-search-engine-indexer", batch = "true")
    void on(List<ConsumerRecord<String, String>> records) throws Exception {
        Set<Long> ids = CatalogChangeKeys.productIds(records);
        if (ids.isEmpty()) {
            return;
        }
        Set<Long> missing = new HashSet<>(ids);
        StringBuilder bulk = new StringBuilder();
        for (LuceneProductSearch.Doc d : docs.byIds(ids)) {
            missing.remove(d.productId());
            bulk.append(json.writeValueAsString(Map.of("index", Map.of("_index", index, "_id", Long.toString(d.productId())))))
                    .append('\n').append(json.writeValueAsString(document(d))).append('\n');
        }
        for (Long id : missing) {
            bulk.append(json.writeValueAsString(Map.of("delete", Map.of("_index", index, "_id", Long.toString(id)))))
                    .append('\n');
        }
        Map<?, ?> res = client.post().uri("/_bulk").contentType(NDJSON)
                .body(bulk.toString().getBytes(StandardCharsets.UTF_8)).retrieve().body(Map.class);
        if (res != null && Boolean.TRUE.equals(res.get("errors"))) {
            checkItems((List<?>) res.get("items"));
        }
    }

    /** 다시 보내면 나을 실패가 있으면 던지고, 문서 자체가 거절된 것은 세고 넘긴다. */
    private void checkItems(List<?> items) {
        for (Object item : items) {
            Map<?, ?> result = (Map<?, ?>) ((Map<?, ?>) item).values().iterator().next();
            int status = ((Number) result.get("status")).intValue();
            if (status < 300 || (status == 404 && result.containsKey("result"))) {
                continue;                              // 성공, 또는 이미 없는 문서를 지움
            }
            if (status == 429 || status >= 500) {
                throw new IllegalStateException("색인 bulk 재시도 필요: status " + status + " id " + result.get("_id"));
            }
            rejected.increment();
            log.warn("색인이 문서를 거절했다 — 넘긴다: id={} status={} error={}", result.get("_id"), status, result.get("error"));
        }
    }

    /** {@code tools/search/products-index.json} 의 매핑과 같은 필드. */
    static Map<String, Object> document(LuceneProductSearch.Doc d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.name());
        m.put("product_type", d.productType());
        m.put("description", d.description());
        m.put("category_code", d.categoryCode());
        m.put("subcategory_code", d.subcategoryCode());
        m.put("colour_code", d.colourCode());
        m.put("price", d.price());
        m.put("in_stock", d.inStock());
        m.put("product_type_kw", d.productType());
        return m;
    }
}
