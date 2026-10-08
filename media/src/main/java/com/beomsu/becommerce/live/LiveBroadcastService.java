package com.beomsu.becommerce.live;

import com.beomsu.becommerce.shared.Ulid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 라이브 방송 애플리케이션 서비스 — live 모듈의 공개 진입점(R1·R2·R3).
 *
 * <p>판매자 API({@link #create}·{@link #get})와 MediaMTX 훅 API({@link #authenticatePublish}·
 * {@link #handlePublish}·{@link #handleUnpublish})를 함께 갖는다 — 훅은 인증이 없는 공개
 * 엔드포인트에서 불리므로({@code LiveMediaHooksController}) 민감한 값(스트림 키 원문 재노출
 * 등)을 돌려주지 않는다.
 *
 * <p><b>경로와 비밀을 분리한다(보안 수정)</b> — MediaMTX 경로는 {@code live/{broadcastId}}
 * (공개 id, 시청 화면에 노출돼도 안전)이고, 송출 자격증명(스트림 키)은 경로가 아니라 별도
 * 값으로 받는다({@link #authenticatePublish}의 두 번째 인자). {@link #handlePublish}·
 * {@link #handleUnpublish}는 "이미 인증을 통과한 뒤"의 훅이라 비밀이 필요 없다 — id만으로
 * 찾는다. 근거는 ADR-084.
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
     * MediaMTX HTTP 인증 훅(R2) — {@code path}(예: {@code "live/42"}, 방송 공개 id)가 가리키는
     * 방송이 있고, 그 방송의 스트림 키가 {@code providedSecret}(RTMP URL의 {@code ?pass=}
     * 쿼리로 온 값)과 일치하며, 상태가 SCHEDULED·LIVE 중 하나면 송출을 허용한다. 그 외(방송
     * 없음, 키 불일치, ENDED 등)는 전부 거절한다 — <b>MediaMTX에 돌려주는 응답(2xx/비2xx)은
     * 어느 쪽인지 구분해 주지 않는다</b>(스트림 키 추측 공격에 정보를 주지 않는다). 거절
     * 사유는 운영 로그에만 남기고, <b>로그에도 스트림 키 원문은 남기지 않는다</b>(방송 id만).
     */
    @Transactional(readOnly = true)
    public boolean authenticatePublish(String path, String providedSecret) {
        Long broadcastId = LiveStreamPaths.broadcastIdFrom(path);
        if (broadcastId == null) {
            log.warn("라이브 송출 인증 거절: 알 수 없는 경로 path={}", path);
            return false;
        }
        Optional<LiveBroadcast> broadcast = repository.findById(broadcastId);
        if (broadcast.isEmpty()) {
            log.warn("라이브 송출 인증 거절: 방송을 찾을 수 없음 id={}", broadcastId);
            return false;
        }
        if (!matchesSecret(broadcast.get().getStreamKey(), providedSecret)) {
            log.warn("라이브 송출 인증 거절: 키 불일치 id={}", broadcastId);
            return false;
        }
        if (!broadcast.get().canAcceptPublish()) {
            log.warn("라이브 송출 인증 거절: 상태 불일치 id={} status={}", broadcastId, broadcast.get().getStatus());
            return false;
        }
        return true;
    }

    /**
     * 송출 시작 훅(R3) — SCHEDULED에서 처음 붙으면 LIVE로 바꾸고 {@code live.started}를
     * 발행한다. 이미 LIVE인 재접속(유예 안)이면 끊김 기록만 지우고 새 이벤트는 없다. 이 훅은
     * 인증을 이미 통과한 뒤에만 오므로(MediaMTX가 publish를 그렇게만 받아들인다) 비밀을 다시
     * 묻지 않는다 — id를 모르는 경로(방어적으로만 대비)는 조용히 무시한다.
     */
    public void handlePublish(String path) {
        Long broadcastId = LiveStreamPaths.broadcastIdFrom(path);
        if (broadcastId == null) {
            log.warn("라이브 송출 시작 훅: 알 수 없는 경로 path={}", path);
            return;
        }
        repository.findById(broadcastId).ifPresentOrElse(broadcast -> {
            Instant now = clock.instant();
            boolean justStarted = broadcast.startOrResumePublish(now);
            if (justStarted) {
                eventPublisher.publishEvent(new LiveStartedEvent(broadcast.getId(), now));
            }
        }, () -> log.warn("라이브 송출 시작 훅: 방송을 찾을 수 없음 id={}", broadcastId));
    }

    /** 송출 끊김 훅(R3) — LIVE면 끊긴 시각만 기록한다(재접속 유예 시작, 상태는 그대로 LIVE). */
    public void handleUnpublish(String path) {
        Long broadcastId = LiveStreamPaths.broadcastIdFrom(path);
        if (broadcastId == null) {
            log.warn("라이브 송출 종료 훅: 알 수 없는 경로 path={}", path);
            return;
        }
        repository.findById(broadcastId)
                .ifPresentOrElse(broadcast -> broadcast.recordDisconnect(clock.instant()),
                        () -> log.warn("라이브 송출 종료 훅: 방송을 찾을 수 없음 id={}", broadcastId));
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

    /**
     * 상수 시간에 가깝게 비교한다(타이밍 사이드채널로 키를 한 글자씩 추측하는 공격을 어렵게
     * 한다) — {@link java.security.MessageDigest#isEqual}이 바로 그 용도로 만들어졌다.
     * {@code providedSecret}이 null이면(쿼리에 아예 안 실려 온 경우, 예: 시청 경로만 아는
     * 사람이 비밀 없이 송출을 시도) 즉시 거절한다.
     */
    private static boolean matchesSecret(String storedStreamKey, String providedSecret) {
        if (providedSecret == null) {
            return false;
        }
        return MessageDigest.isEqual(
                storedStreamKey.getBytes(StandardCharsets.UTF_8),
                providedSecret.getBytes(StandardCharsets.UTF_8));
    }

    private LiveBroadcast findOwned(long sellerId, long id) {
        LiveBroadcast broadcast = repository.findById(id).orElseThrow(() -> LiveBroadcastException.notFound(id));
        if (broadcast.getSellerId() != sellerId) {
            throw LiveBroadcastException.forbidden(id);
        }
        return broadcast;
    }
}
