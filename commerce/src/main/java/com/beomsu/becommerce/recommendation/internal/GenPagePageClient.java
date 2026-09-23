package com.beomsu.becommerce.recommendation.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * GenPage 모델 서버의 {@code /page} — <b>행(대분류)과 그 안의 상품을 모델이 생성한다</b>(#238, ADR-053).
 *
 * <p>서버는 매 단계 마스크로 규칙을 지킨다: 이미 나온 상품, 앞 쪽에서 보여 준 상품({@code exclude}), 다른 대분류의
 * 상품은 생성하지 않는다. 행마다 앞 {@code prefix} 개만 한 칸씩 생성하고 나머지는 한 번에 고른다(하이브리드 행
 * 디코딩). 품절은 모른다 — 호출자가 생성 뒤에 거른다.
 */
@ConditionalOnProperty(name = "app.recommendation.model.kind", havingValue = "genpage")
@Component
public class GenPagePageClient {

    /** 모델이 만든 행 하나. */
    public record Row(String category, List<Long> itemIds) {
    }

    private final RestClient client;
    private final int prefix;

    public GenPagePageClient(@Value("${app.recommendation.model.genpage-url:http://localhost:8765}") String url,
                             @Value("${app.recommendation.model.genpage-timeout:300ms}") Duration timeout,
                             @Value("${app.recommendation.model.genpage-prefix:2}") int prefix) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.prefix = Math.max(prefix, 0);
    }

    public List<Row> generate(List<Long> history, Collection<Long> exclude, Collection<String> excludeCategories,
                              int rows, int itemsPerRow) {
        JsonNode body = client.post().uri("/page").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("history", history, "exclude", exclude, "exclude_categories", excludeCategories,
                        "rows", rows, "items_per_row", itemsPerRow, "prefix", prefix))
                .retrieve().body(JsonNode.class);
        List<Row> out = new ArrayList<>();
        if (body == null) {
            return out;
        }
        if (body.path("violations").asInt(0) != 0) {
            // 서버가 스스로 센 규칙 위반. 0 이 아니면 마스크가 틀린 것이다 — 그 페이지를 쓰지 않는다
            throw new IllegalStateException("GenPage 서버가 규칙 위반 " + body.path("violations").asInt() + "건을 냈다");
        }
        for (JsonNode row : body.path("rows")) {
            List<Long> ids = new ArrayList<>();
            row.path("items").forEach(n -> ids.add(n.asLong()));
            out.add(new Row(row.path("category").asText(), ids));
        }
        return out;
    }
}
