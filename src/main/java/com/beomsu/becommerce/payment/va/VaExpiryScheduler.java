package com.beomsu.becommerce.payment.va;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * 가상계좌 만료 스케줄러.
 *
 * <p>{@code app.va.expiry.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영). 테스트·기본 부트는
 * 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@link VaExpirySchedulingConfig}가 @EnableScheduling을
 * 같은 게이트로 켠다). 처리 로직은 {@link VirtualAccountService#expireOverdue(Instant)}에 있고, 여기서는
 * 주기만 건다. {@code Instant.now()}는 일반 앱 코드 경로라 그대로 쓴다(도메인 로직은 now를 주입받음).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.va.expiry.enabled", havingValue = "true")
class VaExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(VaExpiryScheduler.class);

    private final VirtualAccountService virtualAccountService;

    @Scheduled(fixedDelayString = "${app.va.expiry.interval-ms:60000}")
    public void run() {
        int processed = virtualAccountService.expireOverdue(Instant.now());
        if (processed > 0) {
            log.info("가상계좌 만료 배치 완료 count={}", processed);
        }
    }
}
