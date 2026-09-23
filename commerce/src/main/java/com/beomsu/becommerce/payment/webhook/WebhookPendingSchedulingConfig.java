package com.beomsu.becommerce.payment.webhook;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 보류 웹훅 재처리 스케줄링 게이트.
 *
 * <p>{@code app.webhook.pending-retry.enabled=true}일 때만 @EnableScheduling이 붙는다.
 * 다른 배치와 같은 규약이다 — 게이트 없이 도는 스케줄러는 테스트·로컬 부팅에서도 돌아 부작용을 낸다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.webhook.pending-retry.enabled", havingValue = "true")
public class WebhookPendingSchedulingConfig {
}
