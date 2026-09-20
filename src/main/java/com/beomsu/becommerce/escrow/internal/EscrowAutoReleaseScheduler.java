package com.beomsu.becommerce.escrow.internal;

import com.beomsu.becommerce.escrow.EscrowService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 에스크로 자동 구매확정(auto-release) 스케줄러.
 *
 * <p>{@code app.escrow.auto-release.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영).
 * 테스트·기본 부트는 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@link EscrowSchedulingConfig}가
 * 같은 게이트로 @EnableScheduling을 켠다). 처리 로직은 {@link EscrowService#autoReleaseDue()}에 있고,
 * 여기서는 주기만 건다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.escrow.auto-release.enabled", havingValue = "true")
class EscrowAutoReleaseScheduler {

    private static final Logger log = LoggerFactory.getLogger(EscrowAutoReleaseScheduler.class);

    private final EscrowService escrowService;

    @Scheduled(fixedDelayString = "${app.escrow.auto-release.interval-ms:60000}")
    public void run() {
        int released = escrowService.autoReleaseDue();
        if (released > 0) {
            log.info("에스크로 자동 릴리스 완료 count={}", released);
        }
    }
}
