package com.beomsu.becommerce.shared.outbox;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 미완료 이벤트 재제출 스케줄링 게이트.
 *
 * <p>{@code app.outbox.resubmit.enabled=true} 일 때만 @EnableScheduling 이 붙는다.
 * {@link OutboxCleanupSchedulingConfig} 와 같은 규약이다 — 스케줄러 빈만 등록되고 @EnableScheduling
 * 이 빠지면 {@code @Scheduled} 가 영원히 안 불린다({@code SchedulerGatePairingTest} 가 이 짝을 강제한다).
 * 꺼져 있으면 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.outbox.resubmit.enabled", havingValue = "true")
class OutboxResubmitSchedulingConfig {
}
