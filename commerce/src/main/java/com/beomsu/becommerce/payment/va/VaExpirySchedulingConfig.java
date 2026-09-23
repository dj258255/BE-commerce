package com.beomsu.becommerce.payment.va;

import com.beomsu.becommerce.payment.recovery.PaymentSchedulingConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 가상계좌 만료 스케줄링 게이트.
 *
 * <p>{@code app.va.expiry.enabled=true}일 때만 @EnableScheduling이 붙어 스케줄링이 켜진다.
 * 프로퍼티가 꺼져 있으면(기본) 이 설정 자체가 뜨지 않아 테스트·부트에 부작용이 없다. 같은 payment
 * 모듈의 복구 스케줄링({@code PaymentSchedulingConfig}, 게이트 {@code app.recovery.enabled})과 별도로
 * 게이트해 각 배치를 독립적으로 켤 수 있게 한다 — 두 게이트가 모두 켜져도 @EnableScheduling은
 * 멱등이라 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.va.expiry.enabled", havingValue = "true")
class VaExpirySchedulingConfig {
}
