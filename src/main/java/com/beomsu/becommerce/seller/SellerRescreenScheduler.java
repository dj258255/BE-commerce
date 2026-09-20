package com.beomsu.becommerce.seller;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 판매자 재대조 스케줄러.
 *
 * <p><b>왜 따로 두나</b>: 이 저장소에는 <b>게이트 없이 항상 도는 배치가 없어야 한다</b>는 규칙이
 * 테스트로 박혀 있다. 등록({@link SellerOnboardingService#register})은 게이트와 무관하게 돌아야
 * 하므로, 스케줄이 붙는 부분만 떼어 프로퍼티로 켠다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.screening.rescreen.enabled", havingValue = "true")
public class SellerRescreenScheduler {

    private final SellerOnboardingService onboarding;

    @Scheduled(cron = "${app.screening.rescreen.cron:0 30 5 * * *}")
    public void run() {
        onboarding.rescreenAll();
    }
}
