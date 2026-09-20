package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
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
 * 로컬 모델(Ollama)로 심사 초안을 쓴다. {@code app.assist.fraud-review-provider=ollama} 일 때만 뜬다.
 *
 * <p>{@code OllamaDraftAdapter} 와 같은 이유로 로컬 모델을 쓴다. 결제 데이터가 외부로 안 나간다.
 *
 * <pre>
 * ollama serve &amp;&amp; ollama pull qwen3:8b
 * APP_ASSIST_FRAUD_REVIEW_PROVIDER=ollama ./gradlew bootRun
 * </pre>
 *
 * <p><b>죽어도 예외를 올리지 않는다.</b> 초안이 없으면 서비스가 템플릿으로 떨어뜨린다.
 * 여기서 예외를 던지면 심사 화면이 통째로 안 뜬다. 초안을 못 만든 것과 심사를 못 하는 것은
 * 다른 일이다.
 */
@Slf4j
@Component
// <b>"빈으로 있는 것"과 "심사 화면에 나가는 것"을 갈랐다.</b> 예전에는 provider 로 둘을
// 한꺼번에 정했는데, 그러면 provider 가 template 일 때 이 어댑터가 아예 안 떠서
// 블라인드 비교가 <b>템플릿 대 템플릿</b>이 됐다. 표본이 영원히 안 쌓인다.
// 이 플래그는 <b>모델을 돌릴지</b>만 정하고, 화면에 무엇이 나갈지는 provider 가 정한다.
@ConditionalOnProperty(name = "app.assist.ollama.fraud-review-enabled", havingValue = "true")
public class OllamaFraudReviewAdapter implements FraudReviewDraftPort {

    private final RestClient client;
    private final FraudReviewPromptBuilder prompts;
    private final String model;

    OllamaFraudReviewAdapter(
            FraudReviewPromptBuilder prompts,
            @Value("${app.assist.ollama.base-url:http://localhost:11434}") String baseUrl,
            @Value("${app.assist.ollama.fraud-review-model:${app.assist.ollama.model:qwen3:8b}}") String model,
            @Value("${app.assist.ollama.fraud-review-timeout-seconds:${app.assist.ollama.timeout-seconds:60}}") long timeoutSeconds) {
        this.prompts = prompts;
        this.model = model;
        // 타임아웃을 명시한다. 첫 호출에서 가중치를 올리느라 오래 걸리는데, 기본 무한 대기면
        // 그 사이 심사 화면이 매달린다.
        var factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
        factory.setReadTimeout((int) Duration.ofSeconds(timeoutSeconds).toMillis());
        this.client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public Optional<String> draft(FraudReviewFacts facts) {
        if (facts == null || facts.firedRules().isEmpty()) {
            return Optional.empty();
        }
        return call(prompts.user(facts), facts.reviewId());
    }

    /** 한 번 부른다. 실패하면 빈손으로 돌려주고 부르는 쪽이 정한다. */
    private Optional<String> call(String userMessage, long reviewId) {
        try {
            Map<?, ?> res = client.post().uri("/api/chat")
                    .body(Map.of(
                            "model", model,
                            "stream", false,
                            // 서술만 시키므로 추론 토큰을 끈다. 켜두면 응답이 두 배 넘게 느려진다.
                            "think", false,
                            // 낮게 둔다. 높이면 표현이 다양해지는 게 아니라 사실에서 벗어날 여지가 는다.
                            "options", Map.of("temperature", 0.2),
                            "messages", List.of(
                                    Map.of("role", "system", "content", prompts.system()),
                                    Map.of("role", "user", "content", userMessage))))
                    .retrieve().body(Map.class);

            String text = Optional.ofNullable(res)
                    .map(r -> r.get("message"))
                    .filter(Map.class::isInstance).map(Map.class::cast)
                    .map(m -> m.get("content"))
                    .map(Object::toString).map(String::trim)
                    .filter(t -> !t.isEmpty())
                    .orElse(null);

            return Optional.ofNullable(text);
        } catch (RuntimeException e) {
            log.warn("[ollama] 심사 초안 실패 model={} review={}", model, reviewId, e);
            return Optional.empty();
        }
    }

    @Override
    public Optional<String> revise(FraudReviewFacts facts, String original, java.util.List<String> issues) {
        if (facts == null || original == null || issues == null || issues.isEmpty()) {
            return Optional.empty();
        }
        return call(prompts.revise(facts, original, issues), facts.reviewId());
    }

    @Override
    public String name() {
        return "ollama:" + model;
    }
}
