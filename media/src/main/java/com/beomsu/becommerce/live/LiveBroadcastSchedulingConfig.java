package com.beomsu.becommerce.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 재접속 유예 스캐너(R3) 스케줄링 게이트 — {@code EscrowSchedulingConfig}와 같은 관례.
 *
 * <p>{@code app.live.grace-scheduler.enabled=true}일 때만 {@code @EnableScheduling}이 붙는다.
 * 다른 모듈의 스케줄링 게이트가 이미 켜져 있어도(같은 @EnableScheduling, 멱등) 안전하고,
 * 이 프로퍼티만 꺼지면(기본값) 이 설정 자체가 뜨지 않아 부작용이 없다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.live.grace-scheduler.enabled", havingValue = "true")
class LiveBroadcastSchedulingConfig {
}
