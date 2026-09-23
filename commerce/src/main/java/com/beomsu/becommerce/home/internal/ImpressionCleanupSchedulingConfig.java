package com.beomsu.becommerce.home.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 노출 기록 정리 스케줄링 게이트.
 *
 * <p>{@code app.home.impression-log.cleanup.enabled=true}일 때만 @EnableScheduling이 붙는다.
 * 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다 — 아웃박스·멱등키 정리와
 * 같은 방식이다. 여러 모듈의 게이트가 동시에 켜져도 스케줄 후처리기는 멱등하게 한 번만 등록된다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.home.impression-log.cleanup.enabled", havingValue = "true")
class ImpressionCleanupSchedulingConfig {
}
