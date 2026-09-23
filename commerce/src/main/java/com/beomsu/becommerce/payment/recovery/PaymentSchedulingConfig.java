package com.beomsu.becommerce.payment.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 미확정 결제 복구 스케줄링 게이트.
 *
 * <p>{@code app.recovery.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다. payment 모듈
 * 내부의 VA 만료 스케줄링({@code payment.va.VaExpirySchedulingConfig}, 게이트 {@code app.va.expiry.enabled})과는
 * 독립적으로 게이트한다 — 두 게이트가 모두 켜져도 @EnableScheduling은 멱등(스케줄 후처리기를
 * 빈 이름으로 한 번만 등록)이라 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true")
// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public class PaymentSchedulingConfig {
}
