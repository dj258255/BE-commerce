package com.beomsu.becommerce.seller.screening;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 제재 명단 갱신 스케줄링 게이트.
 *
 * <p>{@code app.screening.list.un.enabled=true} 일 때만 켠다 — 명단 어댑터와 <b>같은 프로퍼티</b>다.
 * 갱신이 안 돌면 명단이 비어 스크리닝이 전부 보류가 되므로, 어댑터를 켜면 갱신도 같이 켜져야 한다.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.screening.list.un.enabled", havingValue = "true")
public class SanctionsListSchedulingConfig {
}
