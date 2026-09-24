package com.beomsu.becommerce.recommendation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 모델 서버로 나가는 요청의 <b>전선 위 모양</b>(#254). 목(mock)으로는 이 결함이 안 보인다 — 본문 객체는 맞았고,
 * 문제는 길이 없이 chunked 로 나가 파이썬 서버가 빈 본문으로 읽은 것이었다. 그래서 실제 HTTP 서버로 받는다.
 */
class GenPageClientWireTest {

    private final AtomicReference<String> contentLength = new AtomicReference<>();
    private final AtomicReference<String> transferEncoding = new AtomicReference<>();
    private final AtomicReference<JsonNode> body = new AtomicReference<>();
    private HttpServer server;

    private String start(String response) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            contentLength.set(ex.getRequestHeaders().getFirst("Content-Length"));
            transferEncoding.set(ex.getRequestHeaders().getFirst("Transfer-Encoding"));
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

    @Test
    @DisplayName("/recommend 는 길이가 붙은 본문으로 이력을 보낸다(chunked 가 아니다)")
    void recommendSendsLengthDelimitedHistory() throws Exception {
        GenPageModelClient client = new GenPageModelClient(start("{\"items\":[7,8]}"), Duration.ofSeconds(2), 1, 1000, 12);

        assertThat(client.recommend(1L, List.of(5L, 3L))).containsExactly(7L, 8L);

        assertThat(contentLength.get()).isNotNull();
        assertThat(transferEncoding.get()).isNull();
        assertThat(body.get().path("history").toString()).isEqualTo("[5,3]");
    }

    @Test
    @DisplayName("/page 도 길이가 붙은 본문으로 이력과 제외 목록을 보낸다")
    void pageSendsLengthDelimitedBody() throws Exception {
        GenPagePageClient client = new GenPagePageClient(start("{\"rows\":[],\"violations\":0}"), Duration.ofSeconds(2), 2);

        client.generate(List.of(5L), List.of(9L), List.of("kids"), 3, 8);

        assertThat(contentLength.get()).isNotNull();
        assertThat(transferEncoding.get()).isNull();
        assertThat(body.get().path("exclude").toString()).isEqualTo("[9]");
    }
}
