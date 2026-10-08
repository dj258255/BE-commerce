package com.beomsu.becommerce.live;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * MediaMTX 상태를 주기적으로 당겨 읽어(poll) 송출 시작·끊김을 commerce에 반영한다(R3) —
 * MediaMTX의 명령 훅(runOnReady/runOnNotReady)이 공식 이미지에 셸이 없어 실행될 수 없다는
 * 것을 실제로 확인한 뒤(ADR-082) push에서 poll로 바꾼 자리다.
 *
 * <p>도메인 로직은 전혀 바뀌지 않았다 — {@link LiveBroadcastService#handlePublish}/
 * {@link LiveBroadcastService#handleUnpublish}를 그대로 부른다. 바뀐 건 "누가 부르는가"뿐이다
 * (MediaMTX가 훅으로 미는 것 → 이 폴러가 상태를 비교해서 당기는 것).
 *
 * <p><b>끊김은 "처음 알아챈 순간"에만 알린다</b> — {@code disconnectedAt}이 이미 있는
 * LIVE 방송은 다시 {@link LiveBroadcastService#handleUnpublish}를 부르지 않는다
 * ({@link LiveBroadcast#recordDisconnect}가 매번 시각을 지금으로 덮어써, 계속 부르면
 * 재접속 유예가 영원히 안 끝난다 — 이 폴러가 주기마다 돈다는 것 자체가 만드는 함정이라
 * 여기서 막는다).
 *
 * <p>폴링 주기({@code app.live.mediamtx-poller.interval-ms}, 기본 2000ms)만큼 상태 반영이
 * 늦어진다 — 그 대가와 근거는 ADR-082 "폴링 주기와 지연" 절에 적었다.
 */
@Component
@ConditionalOnProperty(name = "app.live.mediamtx-poller.enabled", havingValue = "true")
class MediaMtxPathPoller {

    private static final Logger log = LoggerFactory.getLogger(MediaMtxPathPoller.class);

    private final LiveBroadcastService liveBroadcastService;
    private final LiveBroadcastRepository repository;
    private final MediaMtxPathsSource pathsSource;

    @Autowired
    MediaMtxPathPoller(LiveBroadcastService liveBroadcastService, LiveBroadcastRepository repository,
            MediaMtxPathsSource pathsSource) {
        this.liveBroadcastService = liveBroadcastService;
        this.repository = repository;
        this.pathsSource = pathsSource;
    }

    @Scheduled(fixedDelayString = "${app.live.mediamtx-poller.interval-ms:2000}")
    void poll() {
        Set<String> readyKeys;
        try {
            readyKeys = pathsSource.readyStreamKeys();
        } catch (Exception e) {
            log.warn("MediaMTX Control API 폴링 실패 — 이번 주기는 상태 변화를 못 본다", e);
            return;
        }

        List<LiveBroadcast> liveBroadcasts = repository.findByStatus(LiveBroadcastStatus.LIVE);
        for (LiveBroadcast broadcast : liveBroadcasts) {
            boolean stillReady = readyKeys.contains(broadcast.getStreamKey());
            if (!stillReady && broadcast.getDisconnectedAt() == null) {
                liveBroadcastService.handleUnpublish(LiveStreamPaths.PREFIX + broadcast.getStreamKey());
            }
        }
        for (String key : readyKeys) {
            liveBroadcastService.handlePublish(LiveStreamPaths.PREFIX + key);
        }
    }
}
