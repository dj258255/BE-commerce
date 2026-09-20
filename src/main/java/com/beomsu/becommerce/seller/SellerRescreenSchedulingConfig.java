package com.beomsu.becommerce.seller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 판매자 재대조 스케줄링 게이트. 다른 배치와 같은 규약이다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.screening.rescreen.enabled", havingValue = "true")
public class SellerRescreenSchedulingConfig {
}
