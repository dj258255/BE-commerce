package com.beomsu.becommerce.payment.recovery;

import com.beomsu.becommerce.order.compensation.CompensationScheduler;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미확정(UNKNOWN) 결제 복구 스케줄러.
 *
 * <p>{@code app.recovery.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영). 테스트·기본 부트는
 * 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@link PaymentSchedulingConfig}가 @EnableScheduling을
 * 같은 게이트로 켠다). 처리 로직은 {@link PaymentRecoveryService#recoverUnknownPayments()}에 있고,
 * 여기서는 주기만 건다({@code CompensationScheduler}와 동일한 패턴).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.recovery.enabled", havingValue = "true")
class PaymentRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveryScheduler.class);

    private final PaymentRecoveryService paymentRecoveryService;

    @Scheduled(fixedDelayString = "${app.recovery.interval-ms:60000}")
    public void run() {
        int recovered = paymentRecoveryService.recoverUnknownPayments();
        if (recovered > 0) {
            log.info("미확정 결제 복구 완료 count={}", recovered);
        }
    }
}
