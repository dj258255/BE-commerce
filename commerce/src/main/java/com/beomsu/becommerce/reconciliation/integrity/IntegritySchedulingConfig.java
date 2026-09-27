package com.beomsu.becommerce.reconciliation.integrity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 정합성 점검 스케줄링 게이트. 다른 배치와 같은 규약이다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.integrity.enabled", havingValue = "true")
public class IntegritySchedulingConfig {
}
