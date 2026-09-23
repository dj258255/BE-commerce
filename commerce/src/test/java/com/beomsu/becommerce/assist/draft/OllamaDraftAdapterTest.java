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

/**
 * 모델 어댑터 배관 검증 — <b>모델 없이</b> 스텁 서버로 끝까지 태운다.
 *
 * <p><b>왜 필요한가</b>: 어댑터를 만들어만 두면 검증되지 않은 코드가 남는다. 실제 모델을
 * 붙이는 날 응답 파싱이 틀려 있으면, 그게 모델 문제인지 코드 문제인지 구분하느라 시간을 쓴다.
 * 배관은 지금 고정할 수 있고, 지금 고정해야 한다.
 *
 * <p><b>여기서 재는 것은 품질이 아니다.</b> 스텁이 돌려주는 문장은 내가 정한 것이므로
 * 모델이 잘 쓰는지와는 무관하다. 재는 것은 세 가지다 — 응답을 제대로 꺼내는가,
 * 지어낸 숫자가 {@link NumericProvenanceGuard} 에 실제로 걸리는가, 죽었을 때 조용히 비는가.
 */
class OllamaDraftAdapterTest {

    private HttpServer server;
    private int port;
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        port = server.getAddress().getPort();
        server.createContext("/api/chat", ex -> {
            lastRequestBody.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status.get(), body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private OllamaDraftAdapter adapter() {
        return new OllamaDraftAdapter(new PromptBuilder(new CustomerGlossary(), new DraftExamples(), "current"),
                "http://127.0.0.1:" + port, "stub-model", 5);
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

    private void modelReplies(String text) {
        response.set("{\"message\":{\"role\":\"assistant\",\"content\":\""
                + text.replace("\"", "\\\"") + "\"},\"done\":true}");
    }

    @Test
    @DisplayName("응답에서 본문을 꺼낸다")
    void extractsContent() {
        modelReplies("2026-08-30에 10,000원이 승인되었고 수수료 270원이 확인 중입니다.");
        assertThat(adapter().draft(facts()))
                .contains("2026-08-30에 10,000원이 승인되었고 수수료 270원이 확인 중입니다.");
    }

    @Test
    @DisplayName("모델이 지어낸 숫자는 검증기에 걸린다 — 어댑터를 통과해도 초안은 버려진다")
    void inventedNumberIsCaughtEndToEnd() {
        modelReplies("확인 결과 배송비 3,500원이 추가로 청구되었습니다.");   // 3,500 은 사실에 없다

        FactPack facts = facts();
        String draft = adapter().draft(facts).orElseThrow();

        assertThat(new NumericProvenanceGuard().verify(draft, facts))
                .as("어댑터는 모델 말을 그대로 옮긴다. 거르는 것은 검증기의 일이다")
                .anySatisfy(m -> assertThat(m).contains("출처에 없는 금액").contains("3,500"));
    }

    @Test
    @DisplayName("프롬프트에 쓸 수 있는 값 목록이 실린다 — 지시 없이 검사만 하면 반려율만 오른다")
    void promptCarriesAllowedValues() {
        modelReplies("확인했습니다.");
        adapter().draft(facts());

        assertThat(lastRequestBody.get())
                .contains("10,000").contains("270")          // 쓸 수 있는 금액
                .contains("2026-08-30")                      // 쓸 수 있는 날짜
                .contains("stub-model");
    }

    @Test
    @DisplayName("모델이 5xx 면 빈 값을 준다 — 예외를 올리면 확정 업무가 말려 들어간다")
    void serverErrorYieldsEmpty() {
        status.set(500);
        response.set("{\"error\":\"boom\"}");
        assertThat(adapter().draft(facts())).isEmpty();
    }

    @Test
    @DisplayName("응답 모양이 예상과 다르면 빈 값을 준다 — 억지로 뭔가 만들지 않는다")
    void unexpectedShapeYieldsEmpty() {
        response.set("{\"unexpected\":true}");
        assertThat(adapter().draft(facts())).isEmpty();
    }

    @Test
    @DisplayName("사실이 없으면 모델을 아예 부르지 않는다 — 부를 이유가 없다")
    void doesNotCallModelWithoutFacts() {
        modelReplies("무언가");
        lastRequestBody.set(null);

        FactPack empty = new FactPack("ORD-0", List.of(), java.util.Set.of(),
                java.util.Set.of(), null, true);

        assertThat(adapter().draft(empty)).isEmpty();
        assertThat(lastRequestBody.get()).as("호출 자체가 없어야 한다").isNull();
    }
}
