package com.beomsu.becommerce.live;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R1·R3: 라이브 방송 상태머신(생성, 송출 시작·재접속·끊김, 재접속 유예 판정·종료)을 검증한다. */
class LiveBroadcastTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static LiveBroadcast scheduled() {
        return LiveBroadcast.schedule(1L, "KEY123", "방송 제목", T0);
    }

    @Test
    @DisplayName("R1.1: 방송을 만들면 SCHEDULED이고 스트림 키·제목을 그대로 갖는다")
    void scheduleStartsAtScheduledWithStreamKey() {
        LiveBroadcast b = scheduled();

        assertThat(b.getStatus()).isEqualTo(LiveBroadcastStatus.SCHEDULED);
        assertThat(b.getStreamKey()).isEqualTo("KEY123");
        assertThat(b.getTitle()).isEqualTo("방송 제목");
        assertThat(b.getSellerId()).isEqualTo(1L);
        assertThat(b.getStartedAt()).isNull();
        assertThat(b.getEndedAt()).isNull();
    }

    @Test
    @DisplayName("R1.1 경계: 제목 없이(빈 문자열) 만들려 하면 거절된다")
    void scheduleWithBlankTitleIsRejected() {
        assertThatThrownBy(() -> LiveBroadcast.schedule(1L, "KEY123", "  ", T0))
                .isInstanceOf(LiveBroadcastException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_TITLE");
    }

    @Test
    @DisplayName("R2: SCHEDULED·LIVE는 송출을 받을 수 있고, ENDED는 받을 수 없다")
    void canAcceptPublishOnlyWhenScheduledOrLive() {
        LiveBroadcast b = scheduled();
        assertThat(b.canAcceptPublish()).isTrue(); // SCHEDULED

        b.startOrResumePublish(T0.plusSeconds(1));
        assertThat(b.canAcceptPublish()).isTrue(); // LIVE

        b.recordDisconnect(T0.plusSeconds(10));
        b.endFromGraceTimeout(T0.plusSeconds(100));
        assertThat(b.canAcceptPublish()).isFalse(); // ENDED
    }

    @Test
    @DisplayName("R3.1: 처음 송출이 시작되면 LIVE로 바뀌고 startedAt이 찍히며 true(새 이벤트 신호)를 돌려준다")
    void firstPublishTransitionsToLiveAndSignalsNewEvent() {
        LiveBroadcast b = scheduled();
        Instant startedAt = T0.plusSeconds(5);

        boolean justStarted = b.startOrResumePublish(startedAt);

        assertThat(justStarted).isTrue();
        assertThat(b.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        assertThat(b.getStartedAt()).isEqualTo(startedAt);
    }

    @Test
    @DisplayName("R3.2: 이미 LIVE인 상태에서 끊긴 뒤 유예 안에 재접속하면 상태는 LIVE 그대로이고 false(새 이벤트 없음)를 돌려준다")
    void reconnectWhileLiveDoesNotSignalNewEvent() {
        LiveBroadcast b = scheduled();
        b.startOrResumePublish(T0.plusSeconds(1));
        b.recordDisconnect(T0.plusSeconds(10));

        boolean justStarted = b.startOrResumePublish(T0.plusSeconds(15)); // 5초 뒤 재접속

        assertThat(justStarted).isFalse();
        assertThat(b.getStatus()).isEqualTo(LiveBroadcastStatus.LIVE);
        assertThat(b.getDisconnectedAt()).isNull(); // 재접속으로 유예 해제
    }

    @Test
    @DisplayName("R3 경계: ENDED 상태에서 다시 송출이 오면(비정상 경로) 허용되지 않은 전이로 거절된다")
    void publishAfterEndedIsRejected() {
        LiveBroadcast b = scheduled();
        b.startOrResumePublish(T0.plusSeconds(1));
        b.recordDisconnect(T0.plusSeconds(10));
        b.endFromGraceTimeout(T0.plusSeconds(100));

        assertThatThrownBy(() -> b.startOrResumePublish(T0.plusSeconds(200)))
                .isInstanceOf(LiveBroadcastException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_STATE_TRANSITION");
    }

    @Test
    @DisplayName("R3: 끊김은 LIVE일 때만 기록된다 — SCHEDULED에서 끊김 훅이 와도 조용히 무시한다")
    void disconnectBeforeLiveIsIgnored() {
        LiveBroadcast b = scheduled();

        b.recordDisconnect(T0.plusSeconds(1));

        assertThat(b.getDisconnectedAt()).isNull();
        assertThat(b.getStatus()).isEqualTo(LiveBroadcastStatus.SCHEDULED);
    }

    @Test
    @DisplayName("R3: 재접속 유예(30초)를 안 넘겼으면 만료가 아니다")
    void withinGraceIsNotExpired() {
        LiveBroadcast b = scheduled();
        b.startOrResumePublish(T0.plusSeconds(1));
        b.recordDisconnect(T0.plusSeconds(10));

        boolean expired = b.isGraceExpired(T0.plusSeconds(10).plusSeconds(29), Duration.ofSeconds(30));

        assertThat(expired).isFalse();
    }

    @Test
    @DisplayName("R3 경계: 재접속 유예(30초)를 넘기면 만료다")
    void beyondGraceIsExpired() {
        LiveBroadcast b = scheduled();
        b.startOrResumePublish(T0.plusSeconds(1));
        Instant disconnectedAt = T0.plusSeconds(10);
        b.recordDisconnect(disconnectedAt);

        boolean expired = b.isGraceExpired(disconnectedAt.plusSeconds(31), Duration.ofSeconds(30));

        assertThat(expired).isTrue();
    }

    @Test
    @DisplayName("R3.3: 유예(30초)를 넘겨 종료하면 ENDED·endedAt이 찍히고 disconnectedAt은 지워진다")
    void endFromGraceTimeoutSetsEndedAtAndClearsDisconnectedAt() {
        LiveBroadcast b = scheduled();
        b.startOrResumePublish(T0.plusSeconds(1));
        b.recordDisconnect(T0.plusSeconds(10));
        Instant endedAt = T0.plusSeconds(41);

        b.endFromGraceTimeout(endedAt);

        assertThat(b.getStatus()).isEqualTo(LiveBroadcastStatus.ENDED);
        assertThat(b.getEndedAt()).isEqualTo(endedAt);
        assertThat(b.getDisconnectedAt()).isNull();
    }
}
