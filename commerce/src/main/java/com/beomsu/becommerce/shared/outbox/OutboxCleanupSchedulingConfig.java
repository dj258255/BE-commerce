package com.beomsu.becommerce.shared.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Outbox 완료 이벤트 정리 스케줄링 게이트.
 *
 * <p>{@code app.outbox.cleanup.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다. 여러 모듈의
 * @EnableScheduling 게이트와 동시에 켜져도 스케줄 후처리기는 멱등하게 한 번만 등록되어 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.outbox.cleanup.enabled", havingValue = "true")
class OutboxCleanupSchedulingConfig {
}
