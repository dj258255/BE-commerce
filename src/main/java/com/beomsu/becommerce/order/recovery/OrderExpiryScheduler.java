package com.beomsu.becommerce.order.recovery;

import com.beomsu.becommerce.order.internal.SchedulingConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 주문 만료 스케줄러.
 *
 * <p>{@code app.order.expiry.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영). 테스트·기본 부트는
 * 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@link OrderExpirySchedulingConfig}가 @EnableScheduling을
 * 같은 게이트로 켠다). 처리 로직은 {@link OrderExpiryService#expireOverdue(Instant)}에 있고, 여기서는
 * 주기만 건다. order 모듈엔 보상 스케줄링 게이트({@code SchedulingConfig}, {@code app.compensation.enabled})가
 * 이미 있어 @EnableScheduling이 둘이 되지만, 스케줄 후처리기는 빈 이름으로 한 번만 등록되어 안전하다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.order.expiry.enabled", havingValue = "true")
class OrderExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryScheduler.class);

    private final OrderExpiryService orderExpiryService;

    @Scheduled(fixedDelayString = "${app.order.expiry.interval-ms:60000}")
    public void run() {
        int expired = orderExpiryService.expireOverdue(Instant.now());
        if (expired > 0) {
            log.info("주문 만료 배치 완료 count={}", expired);
        }
    }
}
