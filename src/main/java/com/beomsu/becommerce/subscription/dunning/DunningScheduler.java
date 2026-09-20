package com.beomsu.becommerce.subscription.dunning;

import com.beomsu.becommerce.subscription.internal.SubscriptionSchedulingConfig;
import com.beomsu.becommerce.subscription.internal.SubscriptionService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 구독 정기결제(dunning) 스케줄러.
 *
 * <p>{@code app.dunning.enabled=true}일 때만 빈으로 등록돼 주기 실행된다(운영). 테스트·기본 부트는
 * 프로퍼티가 꺼져 있어 스케줄링 자체가 비활성이다({@link SubscriptionSchedulingConfig}가 @EnableScheduling을
 * 같은 게이트로 켠다). 처리 로직은 {@link SubscriptionService#runBillingCycle(LocalDate)}에 있고, 여기서는
 * 주기만 건다. 청구 주기 특성상 기본 간격을 1시간으로 잡는다. {@code LocalDate.now()}는 일반 앱 코드
 * 경로라 그대로 쓴다(도메인 로직은 today를 주입받음).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.dunning.enabled", havingValue = "true")
// 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public class DunningScheduler {

    private static final Logger log = LoggerFactory.getLogger(DunningScheduler.class);

    private final SubscriptionService subscriptionService;

    @Scheduled(fixedDelayString = "${app.dunning.interval-ms:3600000}")
    public void run() {
        int processed = subscriptionService.runBillingCycle(LocalDate.now());
        if (processed > 0) {
            log.info("구독 청구 주기 배치 완료 count={}", processed);
        }
    }
}
