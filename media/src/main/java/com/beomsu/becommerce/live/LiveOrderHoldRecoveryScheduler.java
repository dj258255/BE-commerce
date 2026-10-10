package com.beomsu.becommerce.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 미결제 선점 반환 스캐너(R13) — {@code app.live.order.hold-recovery.enabled=true}일 때만
 * 빈으로 등록돼 주기 실행된다({@code LiveBroadcastGraceScheduler}와 같은 게이트 방식). 처리
 * 로직은 {@link LiveOrderHoldReconciler#reconcileAll()}에 있고, 여기서는 주기만 건다.
 *
 * <p>테스트는 이 스케줄러를 거치지 않고 {@link LiveOrderHoldReconciler#reconcileAll()}을
 * 직접 불러 시계를 밀어 넣는다(가짜 시간 경과) — 기본 게이트가 실제 5분 대기 없이
 * 결정적으로 돈다.
 */
@Component
@ConditionalOnProperty(name = "app.live.order.hold-recovery.enabled", havingValue = "true")
class LiveOrderHoldRecoveryScheduler {

    private final LiveOrderHoldReconciler reconciler;

    LiveOrderHoldRecoveryScheduler(LiveOrderHoldReconciler reconciler) {
        this.reconciler = reconciler;
    }

    @Scheduled(fixedDelayString = "${app.live.order.hold-recovery.interval-ms:5000}")
    void run() {
        reconciler.reconcileAll();
    }
}
