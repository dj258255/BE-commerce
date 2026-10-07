package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R23: 업로드 완료 후 변환 파이프라인({@link ShortsTranscodeService})이 PROBING → TRANSCODING →
 * READY로 가는지, 실패·재시도·격리가 {@link ShortVideo} 상태머신 그대로 반영되는지를
 * {@link FakeTranscodeRunner}로 결정적으로 검증한다. 실제 FFmpeg는 부르지 않는다.
 */
class ShortsTranscodeServiceTest {

    private static final UploadMeta VALID_META = new UploadMeta(30, 10_000_000L, 1080, 1920, "video/mp4");
    private static final long SHORT_VIDEO_ID = 42L;

    private ShortVideoRepository repository;
    private FakeTranscodeRunner runner;
    private ShortsTranscodeService service;

    @BeforeEach
    void setUp() {
        repository = mock(ShortVideoRepository.class);
        runner = new FakeTranscodeRunner();
        service = new ShortsTranscodeService(repository, runner);
    }

    private ShortVideo givenUploadedVideo(String objectKey) {
        ShortVideo video = ShortVideo.upload(1L, objectKey, VALID_META);
        video.markUploaded();
        when(repository.findById(SHORT_VIDEO_ID)).thenReturn(Optional.of(video));
        return video;
    }

    @Test
    @DisplayName("R23: 가짜 실행기가 항상 성공하면 UPLOADED 영상이 READY까지 가고 산출물 다섯 항목이 모두 기록된다")
    void happyPathReachesReadyWithAllOutputs() {
        ShortVideo video = givenUploadedVideo("shorts/1/happy");

        service.processUploaded(SHORT_VIDEO_ID);

        assertThat(video.getStatus()).isEqualTo(ShortVideoStatus.READY);
        assertThat(video.getRendition1080pPath()).isNotBlank();
        assertThat(video.getRendition720pPath()).isNotBlank();
        assertThat(video.getRendition480pPath()).isNotBlank();
        assertThat(video.getMasterPlaylistPath()).isNotBlank();
        assertThat(video.getThumbnailPath()).isNotBlank();
    }

    @Test
    @DisplayName("R23 경계: probe가 매번 실패하면 재시도 3회를 소진하고 QUARANTINED로 격리된다")
    void persistentProbeFailureExhaustsRetriesAndQuarantines() {
        String objectKey = "shorts/1/always-fails-probe";
        ShortVideo video = givenUploadedVideo(objectKey);
        runner.scriptProbe(objectKey, TranscodeRunner.ProbeResult.fail("코덱을 인식할 수 없음"));

        service.processUploaded(SHORT_VIDEO_ID);

        assertThat(video.getStatus()).isEqualTo(ShortVideoStatus.QUARANTINED);
        assertThat(video.isQuarantined()).isTrue();
        assertThat(video.getRetryCount()).isEqualTo(3);
        assertThat(video.getFailureReason()).isEqualTo("코덱을 인식할 수 없음");
        // 격리된 영상은 변환 산출물을 전혀 갖지 않는다 — probe 단계에서 매번 막혔다.
        assertThat(video.getMasterPlaylistPath()).isNull();
    }

    @Test
    @DisplayName("R23.2: transcode가 성공으로 보고해도 산출물이 불완전하면 실패로 취급되어 결국 격리된다")
    void incompleteOutputIsTreatedAsFailureEvenWhenRunnerReportsOk() {
        String objectKey = "shorts/1/incomplete-output";
        ShortVideo video = givenUploadedVideo(objectKey);
        TranscodeOutput incomplete = new TranscodeOutput(
                objectKey + "/1080/master.m3u8", objectKey + "/720/master.m3u8", objectKey + "/480/master.m3u8",
                objectKey + "/master.m3u8", null); // 썸네일 누락
        runner.scriptTranscode(objectKey, TranscodeRunner.TranscodeResult.ok(incomplete));

        service.processUploaded(SHORT_VIDEO_ID);

        assertThat(video.getStatus()).isEqualTo(ShortVideoStatus.QUARANTINED);
        assertThat(video.getFailureReason()).contains("썸네일");
        assertThat(video.getThumbnailPath()).isNull();
    }
}
