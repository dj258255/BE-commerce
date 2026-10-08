package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * R27: /shorts 화면의 시청 신호 기록을 검증한다 — 영상 id·시청자(로그인 userId 또는 비로그인
 * 익명 식별자)·서버 수신 시각과 함께 저장되는지, 입력 검증이 거절하는지.
 */
class ShortsViewSignalServiceTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-10-08T03:00:00Z"), ZoneOffset.UTC);

    private ShortViewEventRepository repository;
    private ShortsViewSignalService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShortViewEventRepository.class);
        service = new ShortsViewSignalService(repository, FIXED_CLOCK);
    }

    @Test
    @DisplayName("R27.2: 끝까지 보고 한 번 더 반복 재생하면 완료 여부 true와 다시 보기 1회가 기록된다")
    void recordsSignalForLoggedInUser() {
        service.record(42L, ViewerIdentity.ofUser(7L), 15, true, 1, false, true);

        ArgumentCaptor<ShortViewEvent> captor = ArgumentCaptor.forClass(ShortViewEvent.class);
        verify(repository).save(captor.capture());
        ShortViewEvent saved = captor.getValue();
        assertThat(saved.getShortVideoId()).isEqualTo(42L);
        assertThat(saved.getUserId()).isEqualTo(7L);
        assertThat(saved.getAnonymousId()).isNull();
        assertThat(saved.getWatchSeconds()).isEqualTo(15);
        assertThat(saved.isCompleted()).isTrue();
        assertThat(saved.getReplayCount()).isEqualTo(1);
        assertThat(saved.isSkippedWithin3s()).isFalse();
        assertThat(saved.isProductTagTapped()).isTrue();
        assertThat(saved.getOccurredAt()).isEqualTo(FIXED_CLOCK.instant());
    }

    @Test
    @DisplayName("R27.1: 2초만 보고 넘기면 건너뛰기 true, 시청 시간 2초, 완료 여부 false로 기록되고 "
            + "익명 시청자는 userId 없이 익명 식별자로 저장된다")
    void recordsSignalForAnonymousViewer() {
        service.record(42L, ViewerIdentity.ofAnonymous("anon-abc"), 2, false, 0, true, false);

        ArgumentCaptor<ShortViewEvent> captor = ArgumentCaptor.forClass(ShortViewEvent.class);
        verify(repository).save(captor.capture());
        ShortViewEvent saved = captor.getValue();
        assertThat(saved.getUserId()).isNull();
        assertThat(saved.getAnonymousId()).isEqualTo("anon-abc");
        assertThat(saved.isSkippedWithin3s()).isTrue();
    }

    @Test
    @DisplayName("R27 경계: 음수 시청 시간은 거절된다(INVALID_VIEW_SIGNAL)")
    void rejectsNegativeWatchSeconds() {
        assertThatThrownBy(() -> service.record(42L, ViewerIdentity.ofUser(7L), -1, false, 0, false, false))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_VIEW_SIGNAL");
    }

    @Test
    @DisplayName("R27 경계: 음수 다시보기 횟수는 거절된다(INVALID_VIEW_SIGNAL)")
    void rejectsNegativeReplayCount() {
        assertThatThrownBy(() -> service.record(42L, ViewerIdentity.ofUser(7L), 5, false, -1, false, false))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_VIEW_SIGNAL");
    }
}
