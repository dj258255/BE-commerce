package com.beomsu.becommerce.live;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link MediaMtxPathsSource}의 실제 구현 — MediaMTX Control API {@code GET /v3/paths/list}를
 * 읽는다({@code CdcHealthMonitor}와 같은 {@code RestClient} + 타임아웃 관례).
 *
 * <p>엄격한 응답 DTO를 두지 않고 {@link JsonNode}로 느슨하게 읽는다 — MediaMTX 버전마다
 * 필드가 늘거나 줄 수 있고, 이 클래스가 쓰는 건 {@code items[].name}·{@code items[].ready}
 * 둘뿐이다(Jackson strict 바인딩이면 다른 필드 변화에도 깨질 수 있다).
 */
@Component
class RestClientMediaMtxPathsSource implements MediaMtxPathsSource {

    private final RestClient restClient;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    RestClientMediaMtxPathsSource(@Value("${app.live.mediamtx.api-base-url:http://localhost:9997}") String apiBaseUrl) {
        this(RestClient.builder().baseUrl(apiBaseUrl).requestFactory(timeouts()).build());
    }

    RestClientMediaMtxPathsSource(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public Set<String> readyStreamKeys() {
        String body = restClient.get().uri("/v3/paths/list").retrieve().body(String.class);
        Set<String> keys = new HashSet<>();
        try {
            JsonNode root = json.readTree(body == null ? "{}" : body);
            for (JsonNode item : root.path("items")) {
                if (!item.path("ready").asBoolean(false)) {
                    continue;
                }
                String key = LiveStreamPaths.keyFrom(item.path("name").asText(""));
                if (key != null) {
                    keys.add(key);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("MediaMTX Control API 응답을 읽을 수 없습니다: " + body, e);
        }
        return keys;
    }

    private static SimpleClientHttpRequestFactory timeouts() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2_000);
        factory.setReadTimeout(3_000);
        return factory;
    }
}
