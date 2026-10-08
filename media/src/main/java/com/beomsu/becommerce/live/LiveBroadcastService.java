package com.beomsu.becommerce.live;

import com.beomsu.becommerce.shared.Ulid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 라이브 방송 애플리케이션 서비스 — live 모듈의 공개 진입점(R1·R2·R3).
 *
 * <p>판매자 API({@link #create}·{@link #get})와 MediaMTX 훅 API({@link #authenticatePublish}·
 * {@link #handlePublish}·{@link #handleUnpublish})를 함께 갖는다 — 훅은 인증이 없는 공개
 * 엔드포인트에서 불리므로({@code LiveMediaHooksController}) 민감한 값(스트림 키 원문 재노출
 * 등)을 돌려주지 않는다.
 *
 * <p>{@link Clock}을 생성자로 받는다(기본 {@link Clock#systemUTC()}) — {@code QueueService}·
 * {@code StockReservationService}와 같은 이 저장소의 관례로, 재접속 유예(R3, 기본 30초) 같은
 * 시간 의존 로직을 실제로 30초 기다리지 않고 테스트할 수 있게 한다.
 */
@Service
@Transactional
public class LiveBroadcastService {

    private static final Logger log = LoggerFactory.getLogger(LiveBroadcastService.class);

    private final LiveBroadcastRepository repository;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;
    private final Duration reconnectGrace;

    @Autowired
    public LiveBroadcastService(LiveBroadcastRepository repository, ApplicationEventPublisher eventPublisher,
            @Value("${app.live.reconnect-grace-seconds:30}") long reconnectGraceSeconds) {
        this(repository, eventPublisher, Clock.systemUTC(), Duration.ofSeconds(reconnectGraceSeconds));
    }

    LiveBroadcastService(LiveBroadcastRepository repository, ApplicationEventPublisher eventPublisher,
            Clock clock, Duration reconnectGrace) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
        this.reconnectGrace = reconnectGrace;
    }

    /**
     * 방송 생성(R1.1) — SCHEDULED로 만들고 방송 전용 스트림 키를 발급한다. {@code title}이
     * 비어 있으면 {@link LiveBroadcastException#invalidTitle}(400)로 거절한다.
     */
    public LiveBroadcastView create(long sellerId, String title) {
        String streamKey = Ulid.generate();
        LiveBroadcast broadcast = LiveBroadcast.schedule(sellerId, streamKey, title, clock.instant());
        repository.save(broadcast);
        return LiveBroadcastView.withKey(broadcast);
    }

    /** 방송주 본인 조회(R1) — 키를 포함한다. 소유자가 아니면 {@code forbidden}(403, 키 노출 없음). */
    @Transactional(readOnly = true)
    public LiveBroadcastView get(long sellerId, long id) {
        return LiveBroadcastView.withKey(findOwned(sellerId, id));
    }

    /**
     * MediaMTX HTTP 인증 훅(R2) — {@code path}(예: {@code "live/스트림키"})의 스트림 키가
     * 존재하고 방송이 SCHEDULED·LIVE 중 하나면 송출을 허용한다. 그 외(키 불일치, ENDED 등)는
     * 전부 거절한다 — <b>MediaMTX에 돌려주는 응답(2xx/비2xx)은 어느 쪽인지 구분해 주지
     * 않는다</b>(스트림 키 추측 공격에 정보를 주지 않는다). 대신 거절 사유(키 불일치 ·
     * 상태 불일치)는 운영 로그에만 남긴다(R2 인수 조건).
     */
    @Transactional(readOnly = true)
    public boolean authenticatePublish(String path) {
        String key = LiveStreamPaths.keyFrom(path);
        if (key == null) {
            log.warn("라이브 송출 인증 거절: 알 수 없는 경로 path={}", path);
            return false;
        }
        var broadcast = repository.findByStreamKey(key);
        if (broadcast.isEmpty()) {
            log.warn("라이브 송출 인증 거절: 키 불일치 key={}", key);
            return false;
        }
        if (!broadcast.get().canAcceptPublish()) {
            log.warn("라이브 송출 인증 거절: 상태 불일치 key={} status={}", key, broadcast.get().getStatus());
            return false;
        }
        return true;
    }

    /**
     * 송출 시작 훅(R3) — SCHEDULED에서 처음 붙으면 LIVE로 바꾸고 {@code live.started}를
     * 발행한다. 이미 LIVE인 재접속(유예 안)이면 끊김 기록만 지우고 새 이벤트는 없다. 키를
     * 모르는 경로(인증 훅을 거치지 않고 올 수는 없지만 방어적으로)는 조용히 무시한다 — 훅은
     * "이미 일어난 일"을 알리는 자리라 여기서 예외로 송출 자체를 막을 수 없다.
     */
    public void handlePublish(String path) {
        String key = LiveStreamPaths.keyFrom(path);
        if (key == null) {
            log.warn("라이브 송출 시작 훅: 알 수 없는 경로 path={}", path);
            return;
        }
        repository.findByStreamKey(key).ifPresentOrElse(broadcast -> {
            Instant now = clock.instant();
            boolean justStarted = broadcast.startOrResumePublish(now);
            if (justStarted) {
                eventPublisher.publishEvent(new LiveStartedEvent(broadcast.getId(), now));
            }
        }, () -> log.warn("라이브 송출 시작 훅: 스트림 키를 찾을 수 없음 key={}", key));
    }

    /** 송출 끊김 훅(R3) — LIVE면 끊긴 시각만 기록한다(재접속 유예 시작, 상태는 그대로 LIVE). */
    public void handleUnpublish(String path) {
        String key = LiveStreamPaths.keyFrom(path);
        if (key == null) {
            log.warn("라이브 송출 종료 훅: 알 수 없는 경로 path={}", path);
            return;
        }
        repository.findByStreamKey(key)
                .ifPresentOrElse(broadcast -> broadcast.recordDisconnect(clock.instant()),
                        () -> log.warn("라이브 송출 종료 훅: 스트림 키를 찾을 수 없음 key={}", key));
    }

    /**
     * 재접속 유예(기본 30초)를 넘긴 방송을 ENDED로 끝맺고 {@code live.ended}를 발행한다(R3).
     * {@link LiveBroadcastGraceScheduler}가 주기적으로 부른다 — 숏폼의
     * {@code ShortsTranscodeService}와 달리 외부 I/O(FFmpeg 같은)가 없는 빠른 DB 갱신이라,
     * 한 틱에 여러 건을 한 트랜잭션으로 처리해도 커넥션을 오래 붙잡지 않는다.
     */
    public void endExpiredGraceBroadcasts() {
        Instant now = clock.instant();
        Instant cutoff = now.minus(reconnectGrace);
        List<LiveBroadcast> candidates = repository.findByStatusAndDisconnectedAtBefore(
                LiveBroadcastStatus.LIVE, cutoff);
        for (LiveBroadcast broadcast : candidates) {
            if (!broadcast.isGraceExpired(now, reconnectGrace)) {
                continue; // 후보 쿼리는 넉넉히 걸렀다 — 정확한 판단은 엔티티가 한다
            }
            broadcast.endFromGraceTimeout(now);
            eventPublisher.publishEvent(new LiveEndedEvent(broadcast.getId(), now));
            log.info("라이브 방송 재접속 유예 만료로 종료 id={}", broadcast.getId());
        }
    }

    private LiveBroadcast findOwned(long sellerId, long id) {
        LiveBroadcast broadcast = repository.findById(id).orElseThrow(() -> LiveBroadcastException.notFound(id));
        if (broadcast.getSellerId() != sellerId) {
            throw LiveBroadcastException.forbidden(id);
        }
        return broadcast;
    }
}
