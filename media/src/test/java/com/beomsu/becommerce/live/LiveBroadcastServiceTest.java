package com.beomsu.becommerce.live;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R1·R2·R3: {@link LiveBroadcastService}의 방송 생성, MediaMTX 인증·시작·종료 훅, 재접속
 * 유예 만료 처리를 실제 MediaMTX·30초 대기 없이 결정적으로 검증한다({@link MutableClock}
 * 주입).
 *
 * <p><b>보안 수정(ADR-084)</b>: MediaMTX 경로는 더 이상 스트림 키가 아니라 방송 공개 id다
 * ({@code "live/{id}"}) — 그래서 훅 조회는 {@code findByStreamKey}가 아니라
 * {@code findById}로 바뀌었고, {@link LiveBroadcastService#authenticatePublish}는 비밀을
 * 경로가 아니라 두 번째 인자로 따로 받는다.
 *
 * <p>{@link LiveBroadcast}는 저장 전(id 미발급) 애그리거트도 만들 수 있어, 이벤트와 id를
 * 비교하는 테스트는 {@link ReflectionTestUtils}로 id를 심어 "이미 저장된 것처럼"
 * 만든다({@code ShortsFeedPageTest}와 같은 방식) — 실제 JPA IDENTITY 저장이 하는 일을
 * 목으로 흉내 낸다.
 */
class LiveBroadcastServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration GRACE = Duration.ofSeconds(30);

    private LiveBroadcastRepository repository;
    private ApplicationEventPublisher eventPublisher;
    private MutableClock clock;
    private LiveBroadcastService service;

    @BeforeEach
    void setUp() {
        repository = mock(LiveBroadcastRepository.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        clock = new MutableClock(T0);
        service = new LiveBroadcastService(repository, eventPublisher, clock, GRACE);
        // repository.save가 실제 JPA IDENTITY 저장처럼 id를 채워 준다(목이라 직접 흉내낸다).
        when(repository.save(any(LiveBroadcast.class))).thenAnswer(invocation -> {
            LiveBroadcast saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", 1L);
            }
            return saved;
        });
    }

    private static LiveBroadcast scheduledWithId(long id, long sellerId, String streamKey) {
        LiveBroadcast b = LiveBroadcast.schedule(sellerId, streamKey, "방송 제목", T0);
        ReflectionTestUtils.setField(b, "id", id);
        return b;
    }

    @Test
    @DisplayName("R1.1: 방송을 만들면 201에 해당하는 뷰(SCHEDULED + 제목 + 스트림 키)를 돌려주고 저장한다")
    void createSchedulesBroadcastWithStreamKey() {
        LiveBroadcastView view = service.create(42L, "오늘의 방송");

        assertThat(view.status()).isEqualTo(LiveBroadcastStatus.SCHEDULED);
        assertThat(view.title()).isEqualTo("오늘의 방송");
        assertThat(view.streamKey()).isNotBlank();
        verify(repository).save(any(LiveBroadcast.class));
    }

    @Test
    @DisplayName("R1.2: 판매자 A의 방송을 판매자 B가 조회하면 403이고 스트림 키는 응답에 없다")
    void getByNonOwnerIsForbidden() {
        LiveBroadcast broadcast = scheduledWithId(5L, 1L, "KEY1");
        when(repository.findById(5L)).thenReturn(Optional.of(broadcast));

        try {
            service.get(999L, 5L);
            org.junit.jupiter.api.Assertions.fail("예외가 나야 한다");
        } catch (LiveBroadcastException e) {
            assertThat(e.code()).isEqualTo("LIVE_BROADCAST_FORBIDDEN");
            assertThat(e.getMessage()).doesNotContain("KEY1"); // 응답에 키가 없다
        }
    }

    @Test
    @DisplayName("R2: 경로의 방송 id와 비밀(스트림 키)이 맞고 SCHEDULED면 송출 인증이 통과한다")
    void authenticatePublishAllowsMatchingSecretWhenScheduled() {
        when(repository.findById(5L)).thenReturn(Optional.of(scheduledWithId(5L, 1L, "KEY1")));

        assertThat(service.authenticatePublish("live/5", "KEY1")).isTrue();
    }

    @Test
    @DisplayName("R2.1: 방송 B1(id=5, 키 key-b1)의 경로로 송출하면서 다른 비밀(key-zzz)을 보내면 거절되고 "
            + "B1 상태는 SCHEDULED로 유지된다")
    void authenticatePublishRejectsMismatchedSecretAndLeavesBroadcastUnaffected() {
        LiveBroadcast b1 = scheduledWithId(5L, 1L, "key-b1");
        when(repository.findById(5L)).thenReturn(Optional.of(b1));

        assertThat(service.authenticatePublish("live/5", "key-zzz")).isFalse();
        assertThat(b1.getStatus()).isEqualTo(LiveBroadcastStatus.SCHEDULED); // 거절은 상태를 바꾸지 않는다
    }

    @Test
    @DisplayName("R2.1 경계: 시청 경로(방송 id)만 알고 비밀을 아예 안 보내면(쿼리 없음) 송출이 거절된다 "
            + "— 시청 URL을 아는 것만으로는 송출할 수 없어야 한다")
    void authenticatePublishRejectsMissingSecret() {
        when(repository.findById(5L)).thenReturn(Optional.of(scheduledWithId(5L, 1L, "key-b1")));

        assertThat(service.authenticatePublish("live/5", null)).isFalse();
    }

    @Test
    @DisplayName("R2.2: 방송이 ENDED면 비밀이 맞아도 송출 인증이 거절된다")
    void authenticatePublishRejectsEndedBroadcast() {
        LiveBroadcast ended = scheduledWithId(5L, 1L, "KEY1");
        ended.startOrResumePublish(T0.plusSeconds(1));
        ended.recordDisconnect(T0.plusSeconds(2));
        ended.endFromGraceTimeout(T0.plusSeconds(100));
        when(repository.findById(5L)).thenReturn(Optional.of(ended));

        assertThat(service.authenticatePublish("live/5", "KEY1")).isFalse();
    }

    @Test
    @DisplayName("R2 경계: live/ 접두사가 없는 경로는 이 체계가 아니므로 거절된다")
    void authenticatePublishRejectsPathWithoutPrefix() {
        assertThat(service.authenticatePublish("other/5", "KEY1")).isFalse();
    }

    @Test
    @DisplayName("R2 경계: 경로에 방송 id가 아닌 값(숫자가 아님)이 오면 거절된다 — 옛 경로(스트림 키 그대로)로 오는 요청도 여기 걸린다")
    void authenticatePublishRejectsNonNumericPath() {
        assertThat(service.authenticatePublish("live/not-a-number", "KEY1")).isFalse();
    }

    @Test
    @DisplayName("R3.1: 올바른 경로(방송 id)로 송출이 시작되면 LIVE로 바뀌고 live.started 이벤트가 1건 발행된다")
    void handlePublishFirstTimeEmitsLiveStarted() {
        LiveBroadcast broadcast = scheduledWithId(7L, 1L, "KEY1");
        when(repository.findById(7L)).thenReturn(Optional.of(broadcast));

        service.handlePublish("live/7");

        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        verify(eventPublisher).publishEvent(new LiveStartedEvent(7L, T0));
    }

    @Test
    @DisplayName("R3.2: 끊긴 뒤 20초 뒤(유예 30초 안) 같은 경로로 다시 붙으면 같은 방송 id가 유지되고 "
            + "상태는 LIVE이며 live.ended는 발행되지 않는다")
    void handlePublishReconnectWithinGraceDoesNotReEmit() {
        LiveBroadcast broadcast = scheduledWithId(7L, 1L, "KEY1");
        when(repository.findById(7L)).thenReturn(Optional.of(broadcast));
        service.handlePublish("live/7"); // 최초 시작
        service.handleUnpublish("live/7"); // 끊김

        clock.set(T0.plusSeconds(20));
        service.handlePublish("live/7"); // 유예(30초) 안 재접속

        verify(eventPublisher, never()).publishEvent(any(LiveEndedEvent.class));
        verify(eventPublisher).publishEvent(any(LiveStartedEvent.class)); // 최초 1회뿐
        assertThat(broadcast.getId()).isEqualTo(7L); // 같은 방송 id가 유지된다
        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        assertThat(broadcast.getDisconnectedAt()).isNull();
    }

    @Test
    @DisplayName("R3: 끊김 훅은 상태를 LIVE로 유지한 채 끊긴 시각만 남긴다")
    void handleUnpublishKeepsLiveAndRecordsDisconnectTime() {
        LiveBroadcast broadcast = scheduledWithId(7L, 1L, "KEY1");
        when(repository.findById(7L)).thenReturn(Optional.of(broadcast));
        service.handlePublish("live/7");

        clock.set(T0.plusSeconds(5));
        service.handleUnpublish("live/7");

        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        assertThat(broadcast.getDisconnectedAt()).isEqualTo(T0.plusSeconds(5));
        verify(eventPublisher, never()).publishEvent(any(LiveEndedEvent.class));
    }

    @Test
    @DisplayName("R3.3: 끊긴 뒤 31초(유예 30초 초과)가 지나면 ENDED로 바뀌고 live.ended 이벤트가 1건 발행된다 "
            + "— 아직 유예 안인 다른 방송은 영향받지 않는다")
    void endExpiredGraceBroadcastsEndsOnlyExpiredOnes() {
        LiveBroadcast expired = scheduledWithId(10L, 1L, "KEY-EXPIRED");
        expired.startOrResumePublish(T0);
        expired.recordDisconnect(T0.plusSeconds(10)); // 유예 끝: T0+40

        LiveBroadcast stillWithinGrace = scheduledWithId(20L, 2L, "KEY-FRESH");
        stillWithinGrace.startOrResumePublish(T0);
        stillWithinGrace.recordDisconnect(T0.plusSeconds(35)); // 유예 끝: T0+65

        clock.set(T0.plusSeconds(41)); // expired는 넘겼고(40<41) fresh는 아직(65>41)
        when(repository.findByStatusAndDisconnectedAtBefore(eq(LiveBroadcastStatus.LIVE), any()))
                .thenReturn(List.of(expired, stillWithinGrace));

        service.endExpiredGraceBroadcasts();

        assertThat(expired.getStatus()).isEqualTo(LiveBroadcastStatus.ENDED);
        assertThat(stillWithinGrace.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        // 정확히 1건(expired, id=10)만 발행됐는지 — fresh(id=20)가 섞여 있지 않은지까지 같이 본다.
        verify(eventPublisher, times(1)).publishEvent(any(LiveEndedEvent.class));
        verify(eventPublisher).publishEvent(new LiveEndedEvent(10L, T0.plusSeconds(41)));
    }
}
