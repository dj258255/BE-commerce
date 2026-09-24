package com.beomsu.becommerce.order.catalog.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * CDC 색인기(#246)의 계약. 둘 다 실측에서 드러난 결함이다.
 *
 * <ul>
 *   <li>본문은 UTF-8 이다 — 문자열로 보냈더니 비 ASCII 설명이 깨져 ES 가 JSON 파싱 오류로 거절했다</li>
 *   <li>문서 하나의 거절은 배치를 멈추지 않는다 — 던졌더니 같은 배치의 다른 변경까지 재시도에 묶였다</li>
 * </ul>
 */
class EngineIndexerTest {

    private static final LuceneProductSearch.Doc DOC = new LuceneProductSearch.Doc(
            7L, "Café dress", "Dress", "Soft crêpe", "ladieswear", null, "black", 30_000L, true);

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://es");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final EngineIndexer indexer = new EngineIndexer(new CatalogDocs(null) {
        @Override
        public List<LuceneProductSearch.Doc> byIds(Collection<Long> ids) {
            return List.of(DOC);
        }
    }, registry, builder, "products");

    private static List<ConsumerRecord<String, String>> change(long id) {
        return List.of(new ConsumerRecord<>("catalog.change", 0, 0L, Long.toString(id), "{}"));
    }

    @Test
    @DisplayName("bulk 본문은 UTF-8 로 보내고 문자셋을 밝힌다")
    void sendsUtf8() throws Exception {
        server.expect(requestTo("http://es/_bulk"))
                .andExpect(header("Content-Type", "application/x-ndjson;charset=UTF-8"))
                .andExpect(content().bytes(expectedBody()))
                .andRespond(withSuccess("{\"errors\":false,\"items\":[]}", MediaType.APPLICATION_JSON));

        indexer.on(change(7));

        server.verify();
    }

    @Test
    @DisplayName("문서가 거절되면(400) 세고 넘기고, 엔진이 못 받으면(503) 던진다")
    void rejectedIsCountedUnavailableThrows() {
        server.expect(requestTo("http://es/_bulk")).andRespond(withSuccess(
                "{\"errors\":true,\"items\":[{\"index\":{\"_id\":\"7\",\"status\":400,\"error\":{\"type\":\"x\"}}}]}",
                MediaType.APPLICATION_JSON));
        server.expect(requestTo("http://es/_bulk")).andRespond(withSuccess(
                "{\"errors\":true,\"items\":[{\"index\":{\"_id\":\"7\",\"status\":503}}]}", MediaType.APPLICATION_JSON));

        try {
            indexer.on(change(7));
        } catch (Exception e) {
            throw new AssertionError("거절은 던지지 않아야 한다", e);
        }
        assertThat(registry.counter("catalog.search.indexer.rejected").count()).isEqualTo(1.0);
        assertThatThrownBy(() -> indexer.on(change(7))).isInstanceOf(IllegalStateException.class);
    }

    private static byte[] expectedBody() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String body = json.writeValueAsString(java.util.Map.of("index", java.util.Map.of("_index", "products", "_id", "7")))
                + "\n" + json.writeValueAsString(EngineIndexer.document(DOC)) + "\n";
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
