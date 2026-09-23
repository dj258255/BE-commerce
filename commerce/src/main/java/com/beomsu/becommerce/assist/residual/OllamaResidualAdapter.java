package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 로컬 Ollama 로 잔여 후보를 고른다.
 *
 * <p><b>파싱이 느슨하면 가드가 무의미해진다.</b> 모델은 형식을 자주 어긴다. 그래서
 * 세 줄을 각각 정규식으로 뽑고, 하나라도 못 뽑으면 기권으로 돌린다. 모델이 뭐라고 썼든
 * {@link ResolveCause}로 변환되지 않으면 그대로 버린다.
 *
 * <p>재시도는 한 번만 한다. Ramp 는 유효한 응답이 나올 때까지 되물었는데, 여기서는
 * 사람이 기다리는 화면 경로라 왕복을 늘릴 여유가 없다. 두 번째도 실패하면 기권이 낫다.
 */
@Component
@ConditionalOnProperty(name = "app.assist.residual-provider", havingValue = "ollama")
public class OllamaResidualAdapter implements ResidualCausePort {

    private static final Logger log = LoggerFactory.getLogger(OllamaResidualAdapter.class);

    private static final Pattern CAUSE = Pattern.compile("CAUSE:\\s*([A-Z_]+)");
    private static final Pattern CONF = Pattern.compile("CONFIDENCE:\\s*(\\d{1,3})");
    private static final Pattern WHY = Pattern.compile("RATIONALE:\\s*(.+)");

    private final RestClient client;
    private final ResidualPromptBuilder prompts;
    private final String model;

    OllamaResidualAdapter(ResidualPromptBuilder prompts,
                          @Value("${app.assist.ollama.base-url:http://localhost:11434}") String baseUrl,
                          @Value("${app.assist.ollama.model:qwen3:14b}") String model,
                          @Value("${app.assist.ollama.timeout-seconds:60}") long timeoutSeconds) {
        this.prompts = prompts;
        this.model = model;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<ResidualSuggestion> suggest(FactPack facts) {
        Optional<ResidualSuggestion> first = ask(facts);
        if (first.isPresent()) {
            return first;
        }
        return ask(facts);   // 형식을 어겼을 때 한 번만 되묻는다
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }

    private Optional<ResidualSuggestion> ask(FactPack facts) {
        String text = chat(prompts.system(), prompts.user(facts), facts.orderNo());
        if (text == null) {
            return Optional.empty();
        }
        return parse(text, facts);
    }

    /** 셋 중 하나라도 못 뽑으면 기권. 목록 밖의 원인이면 기권. */
    static Optional<ResidualSuggestion> parse(String text, FactPack facts) {
        Matcher c = CAUSE.matcher(text);
        Matcher n = CONF.matcher(text);
        Matcher w = WHY.matcher(text);
        if (!c.find() || !n.find() || !w.find()) {
            return Optional.empty();
        }
        ResolveCause cause;
        try {
            cause = ResolveCause.valueOf(c.group(1));
        } catch (IllegalArgumentException e) {
            return Optional.empty();     // ABSTAIN 도, 없는 값도 여기로 온다
        }
        int confidence;
        try {
            confidence = Integer.parseInt(n.group(1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return Optional.of(new ResidualSuggestion(
                cause, w.group(1).trim(), Math.clamp(confidence, 0, 100), facts.amounts()));
    }

    private String chat(String system, String user, String orderNo) {
        try {
            Map<?, ?> res = client.post().uri("/api/chat")
                    .body(Map.of(
                            "model", model,
                            "stream", false,
                            "think", false,
                            // 분류라 서술보다 더 낮춘다. 표현의 다양성이 필요 없는 작업이다.
                            "options", Map.of("temperature", 0.0),
                            "messages", List.of(
                                    Map.of("role", "system", "content", system),
                                    Map.of("role", "user", "content", user))))
                    .retrieve().body(Map.class);

            return Optional.ofNullable(res)
                    .map(r -> r.get("message"))
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .map(m -> m.get("content"))
                    .map(Object::toString).map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .orElse(null);
        } catch (RuntimeException e) {
            log.warn("[residual] ollama 호출 실패 model={} order={}", model, orderNo, e);
            return null;
        }
    }
}
