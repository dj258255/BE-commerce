package com.beomsu.becommerce.assist.draft;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 로컬 심판 — <b>초안을 쓴 모델과 다른 계열</b>이어야 한다 (ADR-014).
 *
 * <p>기본이 {@code llama3.1:8b} 인 것은 취향이 아니다. 초안은 Qwen 이 쓰고,
 * 모델은 자기 계열 출력을 후하게 본다. 같은 계열을 심판으로 세우면
 * <b>통과율만 오르고 무엇도 걸러지지 않는다.</b>
 *
 * <p><b>기동할 때 확인한다.</b> 심판 모델과 초안 모델이 같은 계열이면 경고를 남긴다 —
 * 설정을 잘못 넣어 조용히 무의미해지는 것을 막는다.
 *
 * <p><b>프롬프트에 예시를 세 개 준다.</b> 예시 없이 "근거 없이 단정하는가"만 물었더니
 * 판정과 이유가 서로 모순됐다("단정하고 있습니다"라면서 위반 아님으로 판정).
 * 좋은 예·나쁜 예·중립 예를 주자 3종 모두 맞혔다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.assist.judge.enabled", havingValue = "true")
public class OllamaDraftJudge implements DraftJudge {

    private static final String SYSTEM = """
            당신은 결제사 상담 초안을 검수합니다. 초안을 고치지 말고 <검사만> 하십시오.

            질문 하나만 답하십시오:
            초안이 <고객의 돈>에 대해 단정하는 문장이 있는가? 있다면 그 근거가 [사실]에 있는가?

            예시 1
            사실: 결제 승인 10,000원 / 대사 금액 불일치 내부 10,000 외부 9,730 / 차액 270원은 수수료
            초안: "차액 270원은 결제 수수료이며 청구 금액에는 변동이 없습니다."
            답: {"grounded": true, "quote": "", "why": "승인 금액과 수수료 근거가 사실에 있다"}

            예시 2
            사실: 대사 결과 외부 기록에만 있음. 내부에 이 주문 기록 없음
            초안: "청구 금액은 100,000원으로 그대로 유지됩니다."
            답: {"grounded": false, "quote": "청구 금액은 100,000원으로 그대로 유지됩니다", \
"why": "내부 기록이 없어 청구가 유지된다는 근거가 사실에 없다"}

            예시 3
            사실: 대사 결과 정산 자료 미도착
            초안: "원인을 확인 중이며 확인 후 안내드리겠습니다."
            답: {"grounded": true, "quote": "", "why": "돈에 대해 단정하지 않았다"}

            JSON으로만 답하십시오.""";

    private final RestClient client;
    private final String model;

    OllamaDraftJudge(@Value("${app.assist.ollama.base-url:http://localhost:11434}") String baseUrl,
                     @Value("${app.assist.judge.model:llama3.1:8b}") String model,
                     @Value("${app.assist.ollama.model:qwen3:8b}") String drafterModel,
                     @Value("${app.assist.judge.timeout-seconds:60}") long timeoutSeconds) {
        this.model = model;
        if (sameFamily(model, drafterModel)) {
            log.warn("""
                    [judge] 심판({})과 초안 모델({})이 같은 계열입니다.
                      모델은 자기 계열 출력을 후하게 봅니다. 통과율만 오르고 무엇도 걸러지지 않습니다.
                      app.assist.judge.model 을 다른 계열로 바꾸십시오.""", model, drafterModel);
        }
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    /** {@code qwen3:8b} 와 {@code qwen3:14b} 는 같은 계열이다. 콜론 앞을 본다. */
    private static boolean sameFamily(String a, String b) {
        return family(a).equals(family(b));
    }

    private static String family(String tag) {
        String base = tag == null ? "" : tag.split(":")[0];
        return base.replaceAll("[0-9.\\-]+$", "");     // qwen3 -> qwen, llama3.1 -> llama
    }

    @Override
    public Optional<Verdict> judge(FactPack facts, String draft) {
        if (draft == null || draft.isBlank() || facts.empty()) {
            return Optional.empty();
        }
        try {
            String user = "사실: " + String.join(" / ", facts.facts())
                    + (facts.causeHint() == null ? "" : " / 추정 원인: " + facts.causeHint())
                    + "\n초안: " + draft + "\n답:";

            Map<?, ?> res = client.post().uri("/api/chat")
                    .body(Map.of("model", model, "stream", false,
                            // JSON 강제 — 자유 서술을 파싱하면 판정을 놓친다
                            "format", "json",
                            "options", Map.of("temperature", 0),
                            "messages", List.of(
                                    Map.of("role", "system", "content", SYSTEM),
                                    Map.of("role", "user", "content", user))))
                    .retrieve().body(Map.class);

            String content = Optional.ofNullable(res)
                    .map(r -> r.get("message")).filter(Map.class::isInstance).map(Map.class::cast)
                    .map(m -> m.get("content")).map(Object::toString).orElse(null);
            if (content == null) {
                return Optional.empty();
            }
            var node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(content);
            // 필드가 없으면 <통과로 본다>. 심판이 대답을 못 한 것을 위반으로 세면
            // 심판이 흔들릴 때마다 멀쩡한 초안이 표시된다.
            boolean grounded = !node.has("grounded") || node.get("grounded").asBoolean(true);
            return Optional.of(new Verdict(grounded,
                    node.path("quote").asText(""), node.path("why").asText(""), model));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            log.warn("[judge] 판정 실패 model={} order={}", model, facts.orderNo(), e);
            return Optional.empty();
        }
    }
}
