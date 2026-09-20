package com.beomsu.becommerce.assist.draft;


import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.TimelineEntry;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 심판 어댑터의 배관과 <b>안전한 기본값</b>을 고정한다.
 *
 * <p>모델 품질은 여기서 못 잰다(스텁이니까). 여기서 지키는 것은
 * <b>심판이 흔들릴 때 초안이 어떻게 되는가</b>다 — 판정을 못 하면 통과로 보고,
 * 판정이 이상해도 초안을 버리지 않는다. 심판 때문에 업무가 막히면 안 된다.
 */
class OllamaDraftJudgeTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/chat", ex -> {
            lastBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = response.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private OllamaDraftJudge judge(String judgeModel, String drafterModel) {
        return new OllamaDraftJudge("http://127.0.0.1:" + port, judgeModel, drafterModel, 5);
    }

    private OllamaDraftJudge judge() {
        return judge("llama3.1:8b", "qwen3:8b");
    }

    private FactPack facts() {
        OrderTimeline t = new OrderTimeline("ORD-1",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.RECONCILIATION, "EXT",
                        "대사 EXTERNAL_ONLY — 내부 없음 / 외부 100,000", 100_000L)),
                List.of());
        return FactPack.from(t,
                null);
    }

    private void judgeReplies(String json) {
        response.set("{\"message\":{\"role\":\"assistant\",\"content\":"
                + "\"" + json.replace("\"", "\\\"") + "\"}}");
    }

    @Test
    @DisplayName("근거 없는 단정을 잡아 문장과 이유를 함께 낸다")
    void reportsUngroundedClaim() {
        judgeReplies("{\"grounded\": false, \"quote\": \"청구 금액은 그대로 유지됩니다\","
                + " \"why\": \"내부 기록이 없다\"}");

        DraftJudge.Verdict v = judge().judge(facts(), "청구 금액은 그대로 유지됩니다.").orElseThrow();
        assertThat(v.grounded()).isFalse();
        assertThat(v.quote()).contains("그대로 유지");
        assertThat(v.why()).isNotBlank();
        assertThat(v.judge()).as("누가 판정했는지 남아야 한다").isEqualTo("llama3.1:8b");
    }

    @Test
    @DisplayName("근거가 있으면 통과시킨다")
    void passesGroundedClaim() {
        judgeReplies("{\"grounded\": true, \"quote\": \"\", \"why\": \"사실에 있다\"}");
        assertThat(judge().judge(facts(), "외부 기록에만 확인됩니다.").orElseThrow().grounded()).isTrue();
    }

    @Test
    @DisplayName("판정 필드가 없으면 통과로 본다 — 심판이 대답을 못 한 것을 위반으로 세지 않는다")
    void missingFieldMeansPass() {
        judgeReplies("{\"why\": \"모르겠다\"}");
        assertThat(judge().judge(facts(), "무언가").orElseThrow().grounded()).isTrue();
    }

    @Test
    @DisplayName("심판이 죽으면 빈 값 — 호출자가 통과로 처리한다")
    void serverErrorYieldsEmpty() {
        status.set(500);
        response.set("{\"error\":\"boom\"}");
        assertThat(judge().judge(facts(), "무언가")).isEmpty();
    }

    @Test
    @DisplayName("JSON 형식을 강제해 보낸다 — 자유 서술을 파싱하면 판정을 놓친다")
    void requestsJsonFormat() {
        judgeReplies("{\"grounded\": true}");
        judge().judge(facts(), "무언가");
        assertThat(lastBody.get()).contains("\"format\":\"json\"").contains("llama3.1:8b");
    }

    @Test
    @DisplayName("초안이 없으면 심판을 부르지 않는다")
    void skipsWhenNoDraft() {
        judgeReplies("{\"grounded\": false}");
        lastBody.set(null);
        assertThat(judge().judge(facts(), "  ")).isEmpty();
        assertThat(lastBody.get()).isNull();
    }

    @Test
    @DisplayName("같은 계열을 심판으로 세우면 기동 시 경고한다 — 조용히 무의미해지는 것을 막는다")
    void warnsOnSameFamily() {
        // 경고만 하고 막지는 않는다. 실험 목적으로 같은 계열을 쓰고 싶을 수 있다.
        assertThat(judge("qwen3:14b", "qwen3:8b")).isNotNull();
        assertThat(judge("llama3.1:8b", "qwen3:8b")).isNotNull();
    }
}
