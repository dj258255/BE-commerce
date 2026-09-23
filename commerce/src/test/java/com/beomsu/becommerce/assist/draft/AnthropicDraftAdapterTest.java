package com.beomsu.becommerce.assist.draft;

import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.ResolveCause;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 외부 모델 어댑터 — <b>진짜 키 없이</b> 전 경로를 태운다.
 *
 * <p><b>왜 이렇게 하나</b>: 생성형 AI 는 아직 금융권 망분리 예외가 아니라 실제 호출을
 * 하지 않는다. 그렇다고 어댑터를 <b>검증되지 않은 채로</b> 둘 수는 없다 —
 * 규제가 열리는 날 파싱이 틀려 있으면 그게 모델 문제인지 코드 문제인지 구분하느라 시간을 쓴다.
 *
 * <p>그래서 Anthropic Messages API 의 <b>응답 모양</b>을 흉내내는 서버를 띄운다.
 * 재는 것은 모델 품질이 아니라 배관이다 — 블록 배열에서 텍스트를 제대로 꺼내는가,
 * 헤더를 규격대로 보내는가, 죽었을 때 조용히 비는가.
 */
class AnthropicDraftAdapterTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicReference<String> lastKey = new AtomicReference<>();
    private final AtomicReference<String> lastVersion = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/v1/messages", ex -> {
            lastKey.set(ex.getRequestHeaders().getFirst("x-api-key"));
            lastVersion.set(ex.getRequestHeaders().getFirst("anthropic-version"));
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

    /** 규제가 잠시 풀렸다고 가정한 구성. 키는 <b>테스트용 더미</b>다. */
    private AnthropicDraftAdapter adapter() {
        return new AnthropicDraftAdapter(new PromptBuilder(new CustomerGlossary(), new DraftExamples(), "current"),
                "test-key-not-real", "http://127.0.0.1:" + port, "claude-sonnet-5", 600);
    }

    private FactPack facts() {
        OrderTimeline t = new OrderTimeline("ORD-1",
                List.of(TimelineEntry.of(
                        LocalDate.of(2026, 8, 30).atTime(14, 0)
                                .atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        TimelineEntry.Source.PAYMENT, "PAID", "결제 승인", 10_000L)),
                List.of());
        return FactPack.from(t,
                CauseSuggestion.decisive(ResolveCause.FEE_CALCULATION_DIFF, "FEE_CALCULATION_DIFF (DECISIVE) — 차액 270원", 270L));
    }

    private void replies(String text) {
        response.set("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text.replace("\"", "\\\"") + "\"}]}");
    }

    @Test
    @DisplayName("키가 없으면 기동을 막는다 — 호출 시점에 실패하면 초안이 조용히 비어 나간다")
    void refusesToStartWithoutKey() {
        assertThatThrownBy(() -> new AnthropicDraftAdapter(
                new PromptBuilder(new CustomerGlossary(), new DraftExamples(), "current"),
                "  ", "http://127.0.0.1:" + port, "claude-sonnet-5", 600))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("console.anthropic.com")
                .hasMessageContaining("결제 데이터가 외부로 나갑니다");
    }

    @Test
    @DisplayName("블록 배열에서 텍스트를 꺼낸다")
    void extractsTextBlocks() {
        replies("2026-08-30 결제 10,000원이 정상 확인됩니다.");
        assertThat(adapter().draft(facts())).contains("2026-08-30 결제 10,000원이 정상 확인됩니다.");
    }

    @Test
    @DisplayName("텍스트가 아닌 블록은 건너뛴다 — 도구 호출 블록이 섞여 와도 본문만 쓴다")
    void skipsNonTextBlocks() {
        response.set("""
                {"content":[
                  {"type":"thinking","thinking":"내부 추론"},
                  {"type":"text","text":"고객님, 확인되었습니다."}
                ]}""");
        assertThat(adapter().draft(facts())).contains("고객님, 확인되었습니다.");
    }

    @Test
    @DisplayName("규격대로 헤더를 보낸다 — 키와 API 버전")
    void sendsRequiredHeaders() {
        replies("확인");
        adapter().draft(facts());
        assertThat(lastKey.get()).isEqualTo("test-key-not-real");
        assertThat(lastVersion.get()).as("anthropic-version 누락은 400이 된다").isNotBlank();
        assertThat(lastBody.get()).contains("claude-sonnet-5").contains("max_tokens");
    }

    @Test
    @DisplayName("2단계 수정도 같은 경로로 부른다 — 없으면 프로바이더를 바꿀 때 조용히 꺼진다")
    void supportsRevise() {
        replies("고친 초안입니다. 청구 금액에는 변동이 없습니다.");
        var out = adapter().revise(facts(), "원래 초안", List.of("고객 영향 없음"));

        assertThat(out).as("기본 구현은 빈 값이라 구현하지 않으면 아무 오류 없이 멈춘다").isPresent();
        assertThat(lastBody.get())
                .as("수정 프롬프트가 실려야 한다")
                .contains("빠진 것").contains("원래 초안");
    }

    @Test
    @DisplayName("지적이 없으면 수정을 부르지 않는다")
    void skipsReviseWithoutIssues() {
        replies("무언가");
        lastBody.set(null);
        assertThat(adapter().revise(facts(), "초안", List.of())).isEmpty();
        assertThat(lastBody.get()).isNull();
    }

    @Test
    @DisplayName("5xx 면 빈 값 — 예외를 올리면 확정 업무가 말려 들어간다")
    void serverErrorYieldsEmpty() {
        status.set(500);
        response.set("{\"type\":\"error\",\"error\":{\"type\":\"api_error\"}}");
        assertThat(adapter().draft(facts())).isEmpty();
    }

    @Test
    @DisplayName("응답에 content 가 없으면 빈 값 — 억지로 만들지 않는다")
    void missingContentYieldsEmpty() {
        response.set("{\"id\":\"msg_1\"}");
        assertThat(adapter().draft(facts())).isEmpty();
    }

    @Test
    @DisplayName("사실이 없으면 부르지 않는다 — 토큰을 쓸 이유가 없다")
    void doesNotCallWithoutFacts() {
        replies("무언가");
        lastBody.set(null);
        FactPack empty = new FactPack("ORD-0", List.of(), java.util.Set.of(),
                java.util.Set.of(), null, true);
        assertThat(adapter().draft(empty)).isEmpty();
        assertThat(lastBody.get()).isNull();
    }
}
