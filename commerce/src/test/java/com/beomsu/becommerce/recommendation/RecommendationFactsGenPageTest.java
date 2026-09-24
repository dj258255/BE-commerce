package com.beomsu.becommerce.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.beomsu.becommerce.recommendation.internal.GenPagePageClient;
import com.beomsu.becommerce.recommendation.internal.ItemPoolSource;
import com.beomsu.becommerce.recommendation.internal.RecommendationService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class RecommendationFactsGenPageTest {

    @SuppressWarnings("unchecked")
    private static RecommendationFacts facts(GenPagePageClient client, SimpleMeterRegistry registry) {
        ObjectProvider<GenPagePageClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(client);
        return new RecommendationFacts(mock(RecommendationService.class), mock(ItemPoolSource.class), provider, registry);
    }

    @Test
    @DisplayName("모델이 꺼져 있으면 빈 목록이다 — 홈은 규칙 행을 쓴다")
    void disabledIsEmpty() {
        assertThat(facts(null, new SimpleMeterRegistry()).generatePageRows(List.of(), List.of(), List.of(), 3, 8)).isEmpty();
    }

    @Test
    @DisplayName("모델 서버가 실패하면 예외 대신 빈 목록을 주고 실패를 센다")
    void failureIsEmptyAndCounted() {
        GenPagePageClient client = mock(GenPagePageClient.class);
        when(client.generate(any(), any(), any(), anyInt(), anyInt())).thenThrow(new IllegalStateException("모델 서버가 죽었다"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(facts(client, registry).generatePageRows(List.of(1L), List.of(), List.of(), 3, 8)).isEmpty();
        assertThat(registry.get("recommendation.genpage.page").tag("result", "failed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("모델 자리가 없으면 빈 목록을 주고 실패가 아닌 busy 로 센다(#271)")
    void busyIsEmptyAndCountedSeparately() {
        GenPagePageClient client = mock(GenPagePageClient.class);
        when(client.generate(any(), any(), any(), anyInt(), anyInt()))
                .thenThrow(new com.beomsu.becommerce.recommendation.internal.ModelBusyException("자리 없음"));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        assertThat(facts(client, registry).generatePageRows(List.of(1L), List.of(), List.of(), 3, 8)).isEmpty();
        assertThat(registry.get("recommendation.genpage.page").tag("result", "busy").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("recommendation.genpage.page").tag("result", "failed").counter().count()).isZero();
    }
}
