package com.beomsu.becommerce.recommendation.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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

    /**
     * {@code /page} 응답(S2). {@code compose} 요청에만 서버가 {@code composition} · {@code fallback} 을 싣는다 —
     * 없으면 둘 다 null 이다. 응답의 값은 지표의 태그가 되므로 파싱해 올린다.
     */
    public record Page(List<Row> rows, String composition, String fallback) {
    }

    /** 세션 행동 하나(X5, #328). {@code action} 은 CLICK · VIEW, {@code at} 은 null 이면 서버가 요청일로 본다. */
    public record SessionEvent(long itemId, String action, Instant at) {
    }

    private final RestClient client;
    private final int prefix;
    /** 추천 행과 같이 쓰는 자리(#271). null 이면 자리 없이 부른다(#271 이전의 동작, 비교 측정용). */
    private final GenPageCapacity capacity;

    /** 테스트용: 자리 없이 부른다. */
    public GenPagePageClient(String url, Duration timeout, int prefix) {
        this(url, timeout, prefix, null, "none");
    }

    /**
     * @param pageCapacity {@code shared}(기본) 면 추천 행과 같은 자리를 쓰고, 자리가 없으면 <b>기다리지 않고</b> 포기한다 — 홈은 규칙 행으로
     *                     물러선다. 2쪽은 규칙 행이라는 대안이 있으니 줄을 설 이유가 없다. {@code none} 은 자리 없이 부른다
     */
    @Autowired
    public GenPagePageClient(@Value("${app.recommendation.model.genpage-url:http://localhost:8765}") String url,
                             @Value("${app.recommendation.model.genpage-timeout:300ms}") Duration timeout,
                             @Value("${app.recommendation.model.genpage-prefix:2}") int prefix,
                             GenPageCapacity capacity,
                             @Value("${app.recommendation.model.page-capacity:shared}") String pageCapacity) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.prefix = Math.max(prefix, 0);
        this.capacity = "none".equals(pageCapacity) ? null : capacity;
    }

    public List<Row> generate(List<Long> history, Collection<Long> exclude, Collection<String> excludeCategories,
                              int rows, int itemsPerRow) {
        return generateComposed(history, null, null, exclude, excludeCategories, rows, itemsPerRow, null, null).rows();
    }

    /**
     * 세션을 행동 종류 · 시각과 함께 보낸다(X5, #328). {@code session} 이 null 이면 위의 id 목록 요청과 본문이 같다.
     *
     * @param history 구매(최근 것부터)
     * @param session 조회 · 클릭(<b>오래된 것부터</b> — 서버가 그 순서로 프롬프트에 넣는다)
     * @param now     요청 시각. 서버가 요일 · 월 토큰과 세션의 시각 구간을 이것으로 계산한다
     */
    public List<Row> generate(List<Long> history, List<SessionEvent> session, Instant now, Collection<Long> exclude,
                              Collection<String> excludeCategories, int rows, int itemsPerRow) {
        return generateComposed(history, session, now, exclude, excludeCategories, rows, itemsPerRow, null, null).rows();
    }

    /**
     * 결합 방식({@code compose} · {@code customer})을 실어 보내고 서버가 밝힌 {@code composition} · {@code fallback} 까지
     * 돌려준다(S2, #455). 둘 다 null 이면 본문 · 응답이 위의 호출과 <b>완전히 같다</b> — {@code OFF} 의 회귀가 여기 걸린다.
     *
     * @param compose  {@code rule} · {@code hybrid}. null 이면 보내지 않는다
     * @param customer 고객 키. 서버가 순위 점수 · 이력을 이 키로 찾는다. null 이면 보내지 않는다
     */
    public Page generateComposed(List<Long> history, List<SessionEvent> session, Instant now, Collection<Long> exclude,
                                 Collection<String> excludeCategories, int rows, int itemsPerRow,
                                 String compose, String customer) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("history", history, "exclude", exclude,
                "exclude_categories", excludeCategories, "rows", rows, "items_per_row", itemsPerRow, "prefix", prefix));
        if (session != null) {
            body.put("session", session.stream().map(GenPagePageClient::wire).toList());
            body.put("now", now.toString());
        }
        if (compose != null) {
            body.put("compose", compose);
        }
        if (customer != null) {
            body.put("customer", customer);
        }
        if (capacity == null) {
            return callPage(body);
        }
        if (!capacity.tryAcquire(0)) {
            throw new ModelBusyException("모델 자리가 없다 — 2쪽은 규칙 행으로 간다");
        }
        try {
            return callPage(body);
        } finally {
            capacity.release();
        }
    }

    /** 요청 본문의 세션 원소. 시각은 ISO-8601 문자열이다 — 본문 직렬화기에 시간 모듈이 없다. */
    private static Map<String, Object> wire(SessionEvent event) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("item", event.itemId());
        out.put("action", event.action());
        if (event.at() != null) {
            out.put("at", event.at().toString());
        }
        return out;
    }

    private Page callPage(Map<String, Object> request) {
        JsonNode body = client.post().uri("/page").contentType(MediaType.APPLICATION_JSON)
                .body(ModelRequestBody.of(request))
                .retrieve().body(JsonNode.class);
        List<Row> out = new ArrayList<>();
        if (body == null) {
            return new Page(out, null, null);
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
        // compose 요청에만 있는 필드. 없으면 null 이고, 지표의 태그가 none 이 된다.
        return new Page(out, textOrNull(body, "composition"), textOrNull(body, "fallback"));
    }

    private static String textOrNull(JsonNode body, String field) {
        JsonNode node = body.get(field);
        return node == null || node.isNull() ? null : node.asText();
    }
}
