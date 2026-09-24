package com.beomsu.becommerce.recommendation.internal;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 작은 GenPage 모델 서버를 부르는 {@link ModelClient}(#238, ADR-053).
 *
 * <p>계약은 스텁과 같다 — 이 모듈의 나머지는 모델이 바뀐 줄 모른다. 용량(동시 호출 수)을 넘으면 기다리다
 * {@link ModelBusyException} 을 던지고, 모델 서버가 느리거나 죽으면 예외를 던져 호출자의 폴백(인기)이 받는다.
 *
 * <p><b>생성 중 제약을 받지 않는다</b>({@link #supportsConstrainedGeneration()} = false). 후보마다 재고를 물으면
 * HTTP 왕복이 후보 수만큼 붙는다(ADR-050 에서 로컬 호출로도 6배였다). 품절은 기존대로 생성 뒤에 거른다.
 */
@ConditionalOnProperty(name = "app.recommendation.model.kind", havingValue = "genpage")
@Component
public class GenPageModelClient implements ModelClient {

    private static final Logger log = LoggerFactory.getLogger(GenPageModelClient.class);

    private final RestClient client;
    private final Semaphore capacity;
    private final long busyTimeoutMs;
    private final int resultSize;

    public GenPageModelClient(@Value("${app.recommendation.model.genpage-url:http://localhost:8765}") String url,
                              @Value("${app.recommendation.model.genpage-timeout:300ms}") Duration timeout,
                              @Value("${app.recommendation.model.concurrency:4}") int concurrency,
                              @Value("${app.recommendation.model.busy-timeout-ms:400}") long busyTimeoutMs,
                              @Value("${app.recommendation.result-size:12}") int resultSize) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory).build();
        this.capacity = new Semaphore(Math.max(concurrency, 1), true);
        this.busyTimeoutMs = Math.max(busyTimeoutMs, 1);
        this.resultSize = Math.max(resultSize, 1);
        log.info("GenPage 모델 클라이언트 {} · 제한 시간 {} · 용량 {}동시", url, timeout, Math.max(concurrency, 1));
    }

    @Override
    public List<Long> recommend(long userId, List<Long> recentItemIds) {
        boolean acquired;
        try {
            acquired = capacity.tryAcquire(busyTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ModelBusyException("모델 대기 중 인터럽트됐다");
        }
        if (!acquired) {
            throw new ModelBusyException("모델 용량 대기 초과: " + busyTimeoutMs + "ms");
        }
        try {
            JsonNode body = client.post().uri("/recommend").contentType(MediaType.APPLICATION_JSON)
                    .body(ModelRequestBody.of(Map.of("history", recentItemIds, "k", resultSize)))
                    .retrieve().body(JsonNode.class);
            List<Long> items = new ArrayList<>();
            if (body != null) {
                body.path("items").forEach(n -> items.add(n.asLong()));
            }
            return items;
        } finally {
            capacity.release();
        }
    }
}
