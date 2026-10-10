package com.beomsu.becommerce.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * {@link MediaMtxPathPoller} 스케줄링 게이트 — {@link LiveBroadcastSchedulingConfig}와 같은
 * 관례(독립 프로퍼티로 따로 켜고 끈다, 같은 {@code @EnableScheduling}이 중복돼도 멱등해 안전).
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.live.mediamtx-poller.enabled", havingValue = "true")
class MediaMtxPollerSchedulingConfig {
}
