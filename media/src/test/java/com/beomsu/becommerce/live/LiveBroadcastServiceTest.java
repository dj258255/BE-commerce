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
    @DisplayName("R1 경계: 방송주 본인이 아니면 조회가 거절된다(403, 키가 응답에 없다)")
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
    @DisplayName("R2: 스트림 키가 맞고 SCHEDULED면 송출 인증이 통과한다")
    void authenticatePublishAllowsMatchingKeyWhenScheduled() {
        when(repository.findByStreamKey("KEY1"))
                .thenReturn(Optional.of(LiveBroadcast.schedule(1L, "KEY1", "방송 제목", T0)));

        assertThat(service.authenticatePublish("live/KEY1")).isTrue();
    }

    @Test
    @DisplayName("R2 경계: 스트림 키가 다르면(존재하지 않음) 송출 인증이 거절된다")
    void authenticatePublishRejectsUnknownKey() {
        when(repository.findByStreamKey("WRONG")).thenReturn(Optional.empty());

        assertThat(service.authenticatePublish("live/WRONG")).isFalse();
    }

    @Test
    @DisplayName("R2 경계: 방송이 ENDED면 키가 맞아도 송출 인증이 거절된다")
    void authenticatePublishRejectsEndedBroadcast() {
        LiveBroadcast ended = LiveBroadcast.schedule(1L, "KEY1", "방송 제목", T0);
        ended.startOrResumePublish(T0.plusSeconds(1));
        ended.recordDisconnect(T0.plusSeconds(2));
        ended.endFromGraceTimeout(T0.plusSeconds(100));
        when(repository.findByStreamKey("KEY1")).thenReturn(Optional.of(ended));

        assertThat(service.authenticatePublish("live/KEY1")).isFalse();
    }

    @Test
    @DisplayName("R2 경계: live/ 접두사가 없는 경로는 이 체계가 아니므로 거절된다")
    void authenticatePublishRejectsPathWithoutPrefix() {
        assertThat(service.authenticatePublish("other/KEY1")).isFalse();
    }

    @Test
    @DisplayName("R3: 처음 송출이 시작되면 LIVE로 바뀌고 live.started가 정확히 한 번 발행된다")
    void handlePublishFirstTimeEmitsLiveStarted() {
        LiveBroadcast broadcast = scheduledWithId(7L, 1L, "KEY1");
        when(repository.findByStreamKey("KEY1")).thenReturn(Optional.of(broadcast));

        service.handlePublish("live/KEY1");

        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        verify(eventPublisher).publishEvent(new LiveStartedEvent(7L, T0));
    }

    @Test
    @DisplayName("R3: 유예 안에 재접속하면 live.started가 다시 발행되지 않는다")
    void handlePublishReconnectWithinGraceDoesNotReEmit() {
        LiveBroadcast broadcast = scheduledWithId(7L, 1L, "KEY1");
        when(repository.findByStreamKey("KEY1")).thenReturn(Optional.of(broadcast));
        service.handlePublish("live/KEY1"); // 최초 시작
        service.handleUnpublish("live/KEY1"); // 끊김

        clock.set(T0.plusSeconds(10));
        service.handlePublish("live/KEY1"); // 유예(30초) 안 재접속

        verify(eventPublisher, never()).publishEvent(any(LiveEndedEvent.class));
        verify(eventPublisher).publishEvent(any(LiveStartedEvent.class)); // 최초 1회뿐
        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        assertThat(broadcast.getDisconnectedAt()).isNull();
    }

    @Test
    @DisplayName("R3: 끊김 훅은 상태를 LIVE로 유지한 채 끊긴 시각만 남긴다")
    void handleUnpublishKeepsLiveAndRecordsDisconnectTime() {
        LiveBroadcast broadcast = scheduledWithId(7L, 1L, "KEY1");
        when(repository.findByStreamKey("KEY1")).thenReturn(Optional.of(broadcast));
        service.handlePublish("live/KEY1");

        clock.set(T0.plusSeconds(5));
        service.handleUnpublish("live/KEY1");

        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        assertThat(broadcast.getDisconnectedAt()).isEqualTo(T0.plusSeconds(5));
        verify(eventPublisher, never()).publishEvent(any(LiveEndedEvent.class));
    }

    @Test
    @DisplayName("R3 경계: 재접속 유예(30초)를 넘긴 방송만 ENDED로 끝맺고 live.ended를 발행한다")
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
