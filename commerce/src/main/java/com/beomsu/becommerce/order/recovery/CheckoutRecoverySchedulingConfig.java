package com.beomsu.becommerce.order.recovery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 멈춘 체크아웃 복구 스케줄링 게이트. {@code app.checkout.recovery.enabled=true}일 때만 @EnableScheduling이
 * 붙어 스케줄링이 켜진다(기본 off). 여러 모듈의 게이트와 동시에 켜져도 스케줄 후처리기는 한 번만 등록되어 안전하다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.checkout.recovery.enabled", havingValue = "true")
class CheckoutRecoverySchedulingConfig {
}
