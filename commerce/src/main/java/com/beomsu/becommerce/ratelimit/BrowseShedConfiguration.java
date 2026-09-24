package com.beomsu.becommerce.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 조회 셰딩 필터를 <b>보안 체인보다 앞에</b> 건다(#250). 돌려보낼 요청에 토큰 검증 비용도 쓰지 않는다.
 * 조회 API 는 인증이 필요 없어서 앞에 두어도 잃는 것이 없다.
 */
@Configuration
@ConditionalOnProperty(name = "app.web.browse-shed.enabled", havingValue = "true")
class BrowseShedConfiguration {

    @Bean
    FilterRegistrationBean<BrowseShedFilter> browseShedFilter(
            @Value("${app.web.browse-shed.max-in-flight:16}") int maxInFlight, MeterRegistry registry) {
        FilterRegistrationBean<BrowseShedFilter> bean = new FilterRegistrationBean<>(new BrowseShedFilter(maxInFlight, registry));
        bean.addUrlPatterns("/api/v1/*");
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return bean;
    }
}
