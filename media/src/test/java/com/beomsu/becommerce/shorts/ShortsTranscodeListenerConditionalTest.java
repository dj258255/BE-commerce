package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * R23: {@link ShortsTranscodeListener}는 {@code app.shorts.transcode.enabled=true}(worker
 * 프로파일)일 때만 빈으로 등록된다. API 배포 기본값(꺼짐)에서는 리스너가 없어야 업로드 완료
 * 이벤트를 API 프로세스가 먼저 채가는 일이 없다.
 */
class ShortsTranscodeListenerConditionalTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class, ShortsTranscodeListener.class);

    @Test
    @DisplayName("R23: 프로퍼티가 꺼져 있으면(API 배포 기본값) 변환 리스너 빈이 없다")
    void listenerAbsentByDefault() {
        contextRunner.run(ctx -> assertThat(ctx).doesNotHaveBean(ShortsTranscodeListener.class));
    }

    @Test
    @DisplayName("R23: app.shorts.transcode.enabled=false면 변환 리스너 빈이 없다")
    void listenerAbsentWhenExplicitlyFalse() {
        contextRunner.withPropertyValues("app.shorts.transcode.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ShortsTranscodeListener.class));
    }

    @Test
    @DisplayName("R23: app.shorts.transcode.enabled=true(worker 프로파일)이면 변환 리스너 빈이 등록된다")
    void listenerPresentWhenEnabled() {
        contextRunner.withPropertyValues("app.shorts.transcode.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ShortsTranscodeListener.class));
    }

    @Configuration
    static class TestConfig {
        @Bean
        ShortsTranscodeService shortsTranscodeService() {
            return mock(ShortsTranscodeService.class);
        }
    }
}
