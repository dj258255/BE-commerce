package com.beomsu.becommerce.settlement.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 정산 배치 스케줄링 게이트.
 *
 * <p>{@code app.settlement.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다. 다른 모듈의
 * 스케줄링 게이트들과 @EnableScheduling이 여럿 공존하지만, 스케줄 후처리기는 빈 이름으로 한 번만
 * 등록되어(멱등) 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.settlement.enabled", havingValue = "true")
class SettlementSchedulingConfig {
}
