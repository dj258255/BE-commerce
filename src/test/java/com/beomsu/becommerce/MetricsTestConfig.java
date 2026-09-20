package com.beomsu.becommerce;

import com.beomsu.becommerce.ratelimit.RateLimitFilter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 웹 슬라이스 테스트용 {@link MeterRegistry}.
 *
 * <p>{@code @WebMvcTest}는 메트릭 자동설정을 로드하지 않는다. {@code SecurityConfig}가
 * {@code RateLimitFilter}에 레지스트리를 넘기게 되면서, 시큐리티 설정을 {@code @Import}하는
 * 슬라이스 테스트들이 이 빈을 필요로 한다.
 *
 * <p>목이 아니라 실제 {@link SimpleMeterRegistry}를 준다 — 목이면 계측 코드가 조용히
 * 아무 데도 기록하지 않아도 테스트가 통과한다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MetricsTestConfig {

    @Bean
    MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
