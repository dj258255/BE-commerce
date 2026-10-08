package com.beomsu.becommerce.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R3: {@link MediaMtxPathPoller}가 MediaMTX Control API의 ready 상태와 commerce의 LIVE
 * 방송을 맞대 보고 {@link LiveBroadcastService#handlePublish}/{@code #handleUnpublish}를
 * 올바른 시점에만 부르는지 검증한다 — 공식 MediaMTX 이미지에 셸이 없어 명령 훅
 * (runOnReady/runOnNotReady)을 쓸 수 없다는 것을 확인하고 push에서 poll로 바꾼 자리다
 * (ADR-082). {@link MediaMtxPathsSource}를 가짜로 바꿔 실제 HTTP 호출·MediaMTX 없이 돈다.
 */
class MediaMtxPathPollerTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static LiveBroadcast scheduled(long id, String streamKey) {
        LiveBroadcast b = LiveBroadcast.schedule(1L, streamKey, "방송 제목", T0);
        ReflectionTestUtils.setField(b, "id", id);
        return b;
    }

    private static LiveBroadcast live(long id, String streamKey) {
        LiveBroadcast b = scheduled(id, streamKey);
        b.startOrResumePublish(T0);
        return b;
    }

    private static LiveBroadcast liveDisconnectedAt(long id, String streamKey, Instant disconnectedAt) {
        LiveBroadcast b = live(id, streamKey);
        b.recordDisconnect(disconnectedAt);
        return b;
    }

    /**
     * 실제 {@link LiveBroadcastService}를 쓴다(이벤트 발행만 목으로 흡수) — 폴러가 "기존
     * handlePublish/handleUnpublish를 그대로 부른다"는 설계(ADR-082: 도메인 로직은 안
     * 바뀌었다)를 그 자체로 검증한다.
     */
    private static LiveBroadcastService realService(LiveBroadcastRepository repository) {
        return new LiveBroadcastService(repository, mock(ApplicationEventPublisher.class),
                Clock.fixed(T0, ZoneOffset.UTC), Duration.ofSeconds(30));
    }

    @Test
    @DisplayName("R3.1: ready로 보고된 키의 SCHEDULED 방송은 LIVE로 바뀐다(폴러가 handlePublish를 부른다)")
    void publishesReadyPathsToScheduledBroadcast() {
        LiveBroadcastRepository repository = mock(LiveBroadcastRepository.class);
        LiveBroadcast broadcast = scheduled(1L, "KEY1");
        when(repository.findByStreamKey("KEY1")).thenReturn(Optional.of(broadcast));
        when(repository.findByStatus(LiveBroadcastStatus.LIVE)).thenReturn(List.of());
        LiveBroadcastService service = realService(repository);
        MediaMtxPathsSource source = () -> Set.of("KEY1");
        MediaMtxPathPoller poller = new MediaMtxPathPoller(service, repository, source);

        poller.poll();

        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
    }

    @Test
    @DisplayName("R3: LIVE인데 ready 집합에서 빠지고 아직 끊긴 적 없으면 끊김으로 기록된다(handleUnpublish 호출, 1회)")
    void recordsDisconnectWhenLiveBroadcastDropsOutOfReadySet() {
        LiveBroadcastRepository repository = mock(LiveBroadcastRepository.class);
        LiveBroadcast broadcast = live(1L, "KEY1");
        when(repository.findByStreamKey("KEY1")).thenReturn(Optional.of(broadcast));
        when(repository.findByStatus(LiveBroadcastStatus.LIVE)).thenReturn(List.of(broadcast));
        LiveBroadcastService service = realService(repository);
        MediaMtxPathsSource source = Set::of; // 이제 ready가 아니다(빈 집합)
        MediaMtxPathPoller poller = new MediaMtxPathPoller(service, repository, source);

        poller.poll();

        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        assertThat(broadcast.getDisconnectedAt()).isNotNull();
    }

    @Test
    @DisplayName("R3 경계: 이미 끊긴 시각이 기록된 LIVE 방송은 매 주기 다시 끊김 처리하지 않는다"
            + "(계속 부르면 재접속 유예가 영원히 안 끝나는 함정을 막는다)")
    void doesNotRefreshDisconnectTimeEveryPoll() {
        LiveBroadcastRepository repository = mock(LiveBroadcastRepository.class);
        Instant firstSeen = T0.plusSeconds(5);
        LiveBroadcast broadcast = liveDisconnectedAt(1L, "KEY1", firstSeen);
        when(repository.findByStatus(LiveBroadcastStatus.LIVE)).thenReturn(List.of(broadcast));
        LiveBroadcastService service = mock(LiveBroadcastService.class);
        MediaMtxPathsSource source = Set::of; // 여전히 ready 아님
        MediaMtxPathPoller poller = new MediaMtxPathPoller(service, repository, source);

        poller.poll();

        verify(service, never()).handleUnpublish(any());
        assertThat(broadcast.getDisconnectedAt()).isEqualTo(firstSeen);
    }

    @Test
    @DisplayName("R3.2: 끊긴 적 없이 계속 ready로 보이는 LIVE 방송은 끊김 처리가 되지 않고 LIVE로 유지된다")
    void doesNotDisconnectStillReadyBroadcast() {
        LiveBroadcastRepository repository = mock(LiveBroadcastRepository.class);
        LiveBroadcast broadcast = live(1L, "KEY1");
        when(repository.findByStreamKey("KEY1")).thenReturn(Optional.of(broadcast));
        when(repository.findByStatus(LiveBroadcastStatus.LIVE)).thenReturn(List.of(broadcast));
        LiveBroadcastService service = realService(repository);
        MediaMtxPathsSource source = () -> Set.of("KEY1");
        MediaMtxPathPoller poller = new MediaMtxPathPoller(service, repository, source);

        poller.poll();

        assertThat(broadcast.getDisconnectedAt()).isNull();
        assertThat(broadcast.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
    }

    @Test
    @DisplayName("방어: Control API 조회가 실패하면 이번 주기는 아무것도 바꾸지 않고 조용히 건너뛴다")
    void skipsCycleWhenControlApiFails() {
        LiveBroadcastRepository repository = mock(LiveBroadcastRepository.class);
        LiveBroadcastService service = mock(LiveBroadcastService.class);
        MediaMtxPathsSource failingSource = () -> {
            throw new RuntimeException("MediaMTX에 닿지 않음");
        };
        MediaMtxPathPoller poller = new MediaMtxPathPoller(service, repository, failingSource);

        poller.poll();

        verifyNoInteractions(repository);
        verifyNoInteractions(service);
    }
}
