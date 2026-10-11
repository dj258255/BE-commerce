package com.beomsu.becommerce.live;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 재접속 유예 만료 스캐너(R3) — {@code app.live.grace-scheduler.enabled=true}일 때만 빈으로
 * 등록돼 주기 실행된다({@code EscrowAutoReleaseScheduler}와 같은 게이트 방식). 처리 로직은
 * {@link LiveBroadcastService#endExpiredGraceBroadcasts()}에 있고, 여기서는 주기만 건다.
 *
 * <p>테스트는 이 스케줄러를 거치지 않고 {@code LiveBroadcastService#endExpiredGraceBroadcasts}를
 * 직접 불러 시계를 밀어 넣는다(가짜 시간 경과) — 기본 게이트가 MediaMTX도 실제 30초 대기도
 * 없이 결정적으로 돈다.
 */
@Component
@ConditionalOnProperty(name = "app.live.grace-scheduler.enabled", havingValue = "true")
class LiveBroadcastGraceScheduler {

    private final LiveBroadcastService liveBroadcastService;

    LiveBroadcastGraceScheduler(LiveBroadcastService liveBroadcastService) {
        this.liveBroadcastService = liveBroadcastService;
    }

    @Scheduled(fixedDelayString = "${app.live.grace-scheduler.interval-ms:5000}")
    void run() {
        liveBroadcastService.endExpiredGraceBroadcasts();
    }
}
