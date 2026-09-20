package com.beomsu.becommerce.order.compensation;

import com.beomsu.becommerce.order.internal.SchedulingConfig;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 보상 태스크 재시도 스케줄러.
 *
 * <p>{@code app.compensation.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영). 테스트·기본 부트는
 * 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@code SchedulingConfig}가 @EnableScheduling을 같은
 * 게이트로 켠다). 처리 로직은 {@link CompensationService#processPending()}에 있고, 여기서는 주기만 건다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.compensation.enabled", havingValue = "true")
public class CompensationScheduler {

    private static final Logger log = LoggerFactory.getLogger(CompensationScheduler.class);

    private final CompensationService compensationService;

    @Scheduled(fixedDelayString = "${app.compensation.interval-ms:5000}")
    public void run() {
        int processed = compensationService.processPending();
        if (processed > 0) {
            log.info("보상 태스크 처리 완료 count={}", processed);
        }
    }
}
