package com.beomsu.becommerce.order.recovery;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 멈춘 체크아웃 사가 복구 스케줄러 → {@link CheckoutRecoveryService#recoverStuckCheckouts()}.
 *
 * <p>{@code app.checkout.recovery.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영). 기본 off라
 * 테스트·부트에 부작용이 없다({@link CheckoutRecoverySchedulingConfig}가 @EnableScheduling을 같은 게이트로 켠다).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.checkout.recovery.enabled", havingValue = "true")
class CheckoutRecoveryScheduler {

    private final CheckoutRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${app.checkout.recovery.interval-ms:60000}")
    public void run() {
        recoveryService.recoverStuckCheckouts();
    }
}
