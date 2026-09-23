package com.beomsu.becommerce.subscription.internal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 구독 dunning 스케줄링 게이트.
 *
 * <p>{@code app.dunning.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다. 각 모듈이
 * 자기 스케줄링을 독립적으로 게이트하며, 여러 @EnableScheduling 게이트가 동시에 켜져도 스케줄
 * 후처리기는 멱등하게 한 번만 등록되어 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.dunning.enabled", havingValue = "true")
// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public class SubscriptionSchedulingConfig {
}
