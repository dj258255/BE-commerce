package com.beomsu.becommerce.escrow.internal;

import com.beomsu.becommerce.order.internal.SchedulingConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 에스크로 자동 릴리스 스케줄링 게이트.
 *
 * <p>{@code app.escrow.auto-release.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다. order 모듈의
 * {@code SchedulingConfig}와 독립적으로 각 모듈이 자기 스케줄링을 게이트한다 — 두 게이트가 모두
 * 켜져도 @EnableScheduling은 멱등(스케줄 후처리기를 한 번만 등록)이라 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.escrow.auto-release.enabled", havingValue = "true")
class EscrowSchedulingConfig {
}
