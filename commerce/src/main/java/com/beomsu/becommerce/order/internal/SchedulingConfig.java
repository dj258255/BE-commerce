package com.beomsu.becommerce.order.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링 활성화 게이트.
 *
 * <p>{@code app.compensation.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 스케줄링 인프라 자체가 뜨지 않아 테스트·부트에 부작용이 없다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.compensation.enabled", havingValue = "true")
public class SchedulingConfig {
}
