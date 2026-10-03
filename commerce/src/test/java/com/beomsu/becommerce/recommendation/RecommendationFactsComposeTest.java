package com.beomsu.becommerce.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.personalization.PersonalizationUserMapFacts;
import com.beomsu.becommerce.recommendation.internal.GenPagePageClient;
import com.beomsu.becommerce.recommendation.internal.ItemPoolSource;
import com.beomsu.becommerce.recommendation.internal.ModelBusyException;
import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 홈 2쪽 결합 방식 플래그(S2, #455)의 계약을 실제 HTTP 서버로 고정한다.
 *
 * <p>여기서 지키려는 것은 셋이다.
 * <ul>
 *   <li><b>{@code OFF} 는 지금과 완전히 같다.</b> 본문 직렬화가 기존 호출과 byte 수준으로 같아야 하고,
 *       매핑도 읽지 않고 새 지표도 만들지 않는다</li>
 *   <li><b>매핑이 있으면</b> {@code compose} · {@code customer} 가 실리고 서버가 밝힌 {@code composition} ·
 *       {@code fallback} 이 지표 태그가 된다</li>
 *   <li><b>매핑이 없거나 모델이 실패하면</b> 지금처럼 본문을 보내고 · 빈 목록으로 물러선다</li>
 * </ul>
 */
class RecommendationFactsComposeTest {

    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private HttpServer server;

    private String start(String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            body.set(new ObjectMapper().readTree(ex.getRequestBody().readAllBytes()));
            byte[] out = response.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    @SuppressWarnings("unchecked")
    private static RecommendationFacts facts(GenPagePageClient client, SimpleMeterRegistry registry,
                                             RecommendationFacts.GenPageCompose compose,
                                             PersonalizationUserMapFacts userMap) {
        ObjectProvider<GenPagePageClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        RecommendationService service = mock(RecommendationService.class);
        when(service.purchaseHistoryForModel(anyLong())).thenReturn(null);
        return new RecommendationFacts(service, mock(ItemPoolSource.class), provider, registry, "purchases",
                null, RecommendationFacts.GenPageSession.OFF, 20, compose, userMap);
    }

    @Test
    @DisplayName("OFF 는 본문 직렬화가 지금 호출과 같고, 매핑을 읽지 않고 새 지표도 만들지 않는다(S2)")
    @SuppressWarnings("unchecked")
    void offKeepsBodyAndAddsNoMetric() throws Exception {
        String url = start("{\"rows\":[],\"violations\":0}");
        GenPagePageClient client = new GenPagePageClient(url, Duration.ofSeconds(2), 2);
        PersonalizationUserMapFacts userMap = mock(PersonalizationUserMapFacts.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        // 기준선 — 결합 인자 없는 지금 호출.
        client.generate(List.of(1L, 2L), List.of(9L), List.of("kids"), 3, 8);
        String legacy = body.get().toString();

        body.set(null);
        facts(client, registry, RecommendationFacts.GenPageCompose.OFF, userMap)
                .generatePageRows(7L, List.of(1L, 2L), List.of(9L), List.of("kids"), 3, 8);

        assertThat(body.get().toString()).isEqualTo(legacy);
        assertThat(body.get().has("compose")).isFalse();
        assertThat(body.get().has("customer")).isFalse();
        assertThat(registry.find("recommendation.genpage.compose").counters()).isEmpty();
        verifyNoInteractions(userMap);
    }

    @Test
    @DisplayName("HYBRID + 매핑 있음 → 본문에 compose · customer, 응답 composition · fallback 이 지표 태그(S2)")
    void hybridWithMappingSendsComposeAndCounts() throws Exception {
        String url = start("{\"rows\":[{\"category\":\"kids\",\"items\":[7,8]}],\"violations\":0,"
                + "\"composition\":\"hybrid\",\"fallback\":null}");
        GenPagePageClient client = new GenPagePageClient(url, Duration.ofSeconds(2), 2);
        PersonalizationUserMapFacts userMap = mock(PersonalizationUserMapFacts.class);
        when(userMap.hmCustomerId(7L)).thenReturn(Optional.of("c1"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        List<RecommendationFacts.GeneratedRow> rows =
                facts(client, registry, RecommendationFacts.GenPageCompose.HYBRID, userMap)
                        .generatePageRows(7L, List.of(1L, 2L), List.of(9L), List.of("kids"), 3, 8);

        assertThat(body.get().path("compose").asText()).isEqualTo("hybrid");
        assertThat(body.get().path("customer").asText()).isEqualTo("c1");
        assertThat(rows).containsExactly(new RecommendationFacts.GeneratedRow("kids", List.of(7L, 8L)));
        assertThat(registry.get("recommendation.genpage.compose")
                .tags("composition", "hybrid", "fallback", "none").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("RULE 은 compose=rule 로 보낸다(S2)")
    void ruleSendsRule() throws Exception {
        String url = start("{\"rows\":[],\"violations\":0,\"composition\":\"rule\",\"fallback\":null}");
        GenPagePageClient client = new GenPagePageClient(url, Duration.ofSeconds(2), 2);
        PersonalizationUserMapFacts userMap = mock(PersonalizationUserMapFacts.class);
        when(userMap.hmCustomerId(7L)).thenReturn(Optional.of("c1"));

        facts(client, new SimpleMeterRegistry(), RecommendationFacts.GenPageCompose.RULE, userMap)
                .generatePageRows(7L, List.of(1L, 2L), List.of(), List.of(), 3, 8);

        assertThat(body.get().path("compose").asText()).isEqualTo("rule");
    }

    @Test
    @DisplayName("서버가 점수 없음으로 대체하면 composition=generate · fallback=no_scores 로 센다(S2)")
    void serverFallbackIsCounted() throws Exception {
        String url = start("{\"rows\":[],\"violations\":0,\"composition\":\"generate\",\"fallback\":\"no_scores\"}");
        GenPagePageClient client = new GenPagePageClient(url, Duration.ofSeconds(2), 2);
        PersonalizationUserMapFacts userMap = mock(PersonalizationUserMapFacts.class);
        when(userMap.hmCustomerId(7L)).thenReturn(Optional.of("c1"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        facts(client, registry, RecommendationFacts.GenPageCompose.HYBRID, userMap)
                .generatePageRows(7L, List.of(), List.of(), List.of(), 3, 8);

        assertThat(registry.get("recommendation.genpage.compose")
                .tags("composition", "generate", "fallback", "no_scores").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("HYBRID + 매핑 없음 → 본문은 지금 그대로, 지표 fallback=no_mapping(S2)")
    void hybridWithoutMappingKeepsBodyAndCountsNoMapping() throws Exception {
        String url = start("{\"rows\":[],\"violations\":0}");
        GenPagePageClient client = new GenPagePageClient(url, Duration.ofSeconds(2), 2);
        PersonalizationUserMapFacts userMap = mock(PersonalizationUserMapFacts.class);
        when(userMap.hmCustomerId(7L)).thenReturn(Optional.empty());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        facts(client, registry, RecommendationFacts.GenPageCompose.HYBRID, userMap)
                .generatePageRows(7L, List.of(1L, 2L), List.of(), List.of(), 3, 8);

        assertThat(body.get().has("compose")).isFalse();
        assertThat(body.get().has("customer")).isFalse();
        assertThat(registry.get("recommendation.genpage.compose")
                .tags("composition", "none", "fallback", "no_mapping").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("모델이 실패하면 빈 목록(규칙 행)이고 지표는 none · none 이다(S2)")
    @SuppressWarnings("unchecked")
    void failureIsEmptyAndCounted() {
        GenPagePageClient client = mock(GenPagePageClient.class);
        when(client.generateComposed(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new IllegalStateException("모델 서버가 죽었다"));
        PersonalizationUserMapFacts userMap = mock(PersonalizationUserMapFacts.class);
        when(userMap.hmCustomerId(7L)).thenReturn(Optional.of("c1"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(facts(client, registry, RecommendationFacts.GenPageCompose.HYBRID, userMap)
                .generatePageRows(7L, List.of(), List.of(), List.of(), 3, 8)).isEmpty();
        assertThat(registry.get("recommendation.genpage.compose")
                .tags("composition", "none", "fallback", "none").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recommendation.genpage.page").tag("result", "failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("모델 자리가 없으면 빈 목록(규칙 행)이고 페이지 지표는 busy 로 센다(S2, #271)")
    @SuppressWarnings("unchecked")
    void busyIsEmptyAndCounted() {
        GenPagePageClient client = mock(GenPagePageClient.class);
        when(client.generateComposed(any(), any(), any(), any(), any(), anyInt(), anyInt(), any(), any()))
                .thenThrow(new ModelBusyException("자리 없음"));
        PersonalizationUserMapFacts userMap = mock(PersonalizationUserMapFacts.class);
        when(userMap.hmCustomerId(7L)).thenReturn(Optional.of("c1"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(facts(client, registry, RecommendationFacts.GenPageCompose.HYBRID, userMap)
                .generatePageRows(7L, List.of(), List.of(), List.of(), 3, 8)).isEmpty();
        assertThat(registry.get("recommendation.genpage.page").tag("result", "busy").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recommendation.genpage.page").tag("result", "failed").counter().count()).isZero();
    }
}
