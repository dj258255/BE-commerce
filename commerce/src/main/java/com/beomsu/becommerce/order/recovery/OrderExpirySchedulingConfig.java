package com.beomsu.becommerce.order.recovery;

import com.beomsu.becommerce.order.internal.SchedulingConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 주문 만료 스케줄링 게이트.
 *
 * <p>{@code app.order.expiry.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다. 같은 order
 * 모듈의 보상 스케줄링({@code SchedulingConfig}, 게이트 {@code app.compensation.enabled})과 별도로
 * 게이트해 각 배치를 독립적으로 켤 수 있게 한다 — 두 게이트가 모두 켜져 @EnableScheduling이 둘이
 * 돼도 스케줄 후처리기는 멱등하게 한 번만 등록되어 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.order.expiry.enabled", havingValue = "true")
class OrderExpirySchedulingConfig {
}
