package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R23: 변환 오케스트레이터({@link ShortsTranscodeService})가 {@link ShortVideoTransitionService}
 * (짧은 트랜잭션 단위의 상태 전이)와 {@link TranscodeRunner}(FFmpeg, 트랜잭션 밖)를 올바른
 * 순서로 부르는지 — claim 실패 시 조용히 물러나는지, 실패·재시도·격리 흐름이 맞는지 — 결정적으로
 * 검증한다. {@link ShortVideoTransitionService}는 Mockito로 대체해 실제 DB·트랜잭션 없이
 * 오케스트레이션 로직만 본다(실제 트랜잭션 경계·동시성은 {@code ShortVideoTransitionBoundaryTest}가
 * 본다).
 */
class ShortsTranscodeServiceTest {

    private static final long SHORT_VIDEO_ID = 42L;
    private static final String OBJECT_KEY = "shorts/1/video";

    private ShortVideoTransitionService transitions;
    private ShortsTranscodeService service;

    @BeforeEach
    void setUp() {
        transitions = mock(ShortVideoTransitionService.class);
        when(transitions.objectKeyOf(SHORT_VIDEO_ID)).thenReturn(OBJECT_KEY);
    }

    @Test
    @DisplayName("R23: claim이 실패하면(다른 워커가 이미 처리 중) FFmpeg를 한 번도 부르지 않고 조용히 물러난다")
    void returnsImmediatelyWhenClaimFails() {
        when(transitions.claimForProbing(SHORT_VIDEO_ID)).thenReturn(false);
        TranscodeRunner runner = mock(TranscodeRunner.class);
        service = new ShortsTranscodeService(transitions, runner);

        service.processUploaded(SHORT_VIDEO_ID);

        verifyNoInteractions(runner);
        verify(transitions, never()).objectKeyOf(anyLong());
        verify(transitions, never()).claimForTranscoding(anyLong());
    }

    @Test
    @DisplayName("R22.1: 가짜 실행기가 항상 성공하면 claim→probe→claim→transcode→기록 순서로 한 번씩 불려 READY로 끝난다")
    void happyPathCallsEachStepExactlyOnce() {
        FakeTranscodeRunner runner = new FakeTranscodeRunner();
        when(transitions.claimForProbing(SHORT_VIDEO_ID)).thenReturn(true);
        when(transitions.claimForTranscoding(SHORT_VIDEO_ID)).thenReturn(true);
        when(transitions.recordTranscodeResult(eq(SHORT_VIDEO_ID), any())).thenReturn(ShortVideoStatus.READY);
        service = new ShortsTranscodeService(transitions, runner);

        service.processUploaded(SHORT_VIDEO_ID);

        verify(transitions, times(1)).claimForProbing(SHORT_VIDEO_ID);
        verify(transitions, times(1)).claimForTranscoding(SHORT_VIDEO_ID);
        verify(transitions, times(1)).recordTranscodeResult(eq(SHORT_VIDEO_ID), any());
        verify(transitions, never()).recordFailure(anyLong(), any());
    }

    @Test
    @DisplayName("R22.2: probe가 매번 실패하면(변환이 계속 실패) 3회 재시도(claim→probe) 후 QUARANTINED에서 멈춘다")
    void persistentProbeFailureRetriesThreeTimesThenStops() {
        FakeTranscodeRunner runner = new FakeTranscodeRunner();
        runner.scriptProbe(OBJECT_KEY, TranscodeRunner.ProbeResult.fail("코덱을 인식할 수 없음"));
        when(transitions.claimForProbing(SHORT_VIDEO_ID)).thenReturn(true);
        when(transitions.recordFailure(SHORT_VIDEO_ID, "코덱을 인식할 수 없음"))
                .thenReturn(ShortVideoStatus.FAILED)
                .thenReturn(ShortVideoStatus.FAILED)
                .thenReturn(ShortVideoStatus.QUARANTINED);
        service = new ShortsTranscodeService(transitions, runner);

        service.processUploaded(SHORT_VIDEO_ID);

        verify(transitions, times(3)).claimForProbing(SHORT_VIDEO_ID);
        verify(transitions, times(3)).recordFailure(SHORT_VIDEO_ID, "코덱을 인식할 수 없음");
        verify(transitions, never()).claimForTranscoding(anyLong());
    }

    @Test
    @DisplayName("R23.2: transcode 기록이 산출물 불완전으로 FAILED를 돌려주면 claim부터 다시 돌고, 그다음 QUARANTINED면 멈춘다")
    void incompleteOutputRetriesOnceThenQuarantines() {
        FakeTranscodeRunner runner = new FakeTranscodeRunner();
        when(transitions.claimForProbing(SHORT_VIDEO_ID)).thenReturn(true);
        when(transitions.claimForTranscoding(SHORT_VIDEO_ID)).thenReturn(true);
        when(transitions.recordTranscodeResult(eq(SHORT_VIDEO_ID), any()))
                .thenReturn(ShortVideoStatus.FAILED)
                .thenReturn(ShortVideoStatus.QUARANTINED);
        service = new ShortsTranscodeService(transitions, runner);

        service.processUploaded(SHORT_VIDEO_ID);

        verify(transitions, times(2)).claimForProbing(SHORT_VIDEO_ID);
        verify(transitions, times(2)).claimForTranscoding(SHORT_VIDEO_ID);
        verify(transitions, times(2)).recordTranscodeResult(eq(SHORT_VIDEO_ID), any());
        verify(transitions, never()).recordFailure(anyLong(), any());
    }

    @Test
    @DisplayName("R23: PROBING→TRANSCODING claim이 실패하면(예상 밖 경쟁) transcode를 부르지 않고 멈춘다")
    void stopsWhenTranscodingClaimFails() {
        TranscodeRunner runner = mock(TranscodeRunner.class);
        when(runner.probe(OBJECT_KEY)).thenReturn(TranscodeRunner.ProbeResult.ok());
        when(transitions.claimForProbing(SHORT_VIDEO_ID)).thenReturn(true);
        when(transitions.claimForTranscoding(SHORT_VIDEO_ID)).thenReturn(false);
        service = new ShortsTranscodeService(transitions, runner);

        service.processUploaded(SHORT_VIDEO_ID);

        verify(runner, never()).transcode(any());
        verify(transitions, never()).recordTranscodeResult(anyLong(), any());
    }

    @Test
    @DisplayName("R23: 가짜 실행기가 기본으로 돌려주는 산출물 다섯 항목은 모두 비어 있지 않다")
    void fakeRunnerDefaultOutputIsComplete() {
        FakeTranscodeRunner runner = new FakeTranscodeRunner();

        TranscodeRunner.TranscodeResult result = runner.transcode(OBJECT_KEY);

        assertThat(result.success()).isTrue();
        assertThat(result.output().missingField()).isNull();
    }
}
