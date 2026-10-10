package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R22: 숏폼 상태머신(전이 허용/거절, 실패 사유, 재시도 3회 뒤 격리)을 검증한다. */
class ShortVideoTest {

    private static final UploadMeta VALID_META = new UploadMeta(30, 10_000_000L, 1080, 1920, "video/mp4");

    private ShortVideo video() {
        return ShortVideo.upload(1L, "shorts/1/" + System.nanoTime(), VALID_META);
    }

    @Test
    @DisplayName("R22: 업로드 직후 UPLOADING, retryCount=0")
    void uploadStartsAtUploading() {
        ShortVideo v = video();
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.UPLOADING);
        assertThat(v.getRetryCount()).isEqualTo(0);
        assertThat(v.getMaxRetries()).isEqualTo(3);
        assertThat(v.isQuarantined()).isFalse();
    }

    @Test
    @DisplayName("R22.1: 업로드가 끝난 영상을 정상 처리하면 UPLOADED→PROBING→TRANSCODING→READY로 전이된다")
    void happyPathReachesReady() {
        ShortVideo v = video();

        v.markUploaded();
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.UPLOADED);

        v.startProbing();
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.PROBING);

        v.startTranscoding();
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.TRANSCODING);

        v.markReady();
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.READY);
    }

    @Test
    @DisplayName("R22: 허용되지 않은 전이는 거절 — UPLOADING에서 바로 PROBING 불가")
    void skippingStepsIsRejected() {
        ShortVideo v = video();

        assertThatThrownBy(v::startProbing)
                .isInstanceOf(ShortsException.class)
                .hasMessageContaining("UPLOADING")
                .hasMessageContaining("PROBING");
    }

    @Test
    @DisplayName("R22: 허용되지 않은 전이는 거절 — READY(종료 상태)에서는 아무 전이도 안 된다")
    void terminalReadyRejectsAnyTransition() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();
        v.startTranscoding();
        v.markReady();

        assertThatThrownBy(v::startProbing).isInstanceOf(ShortsException.class);
        assertThatThrownBy(() -> v.fail("안 됨")).isInstanceOf(ShortsException.class);
    }

    @Test
    @DisplayName("R22: 실패 기록 — FAILED로 전이하고 사유를 남기며 retryCount를 올린다")
    void failRecordsReasonAndIncrementsRetryCount() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();

        v.fail("세로 비율 아님");

        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.FAILED);
        assertThat(v.getFailureReason()).isEqualTo("세로 비율 아님");
        assertThat(v.getRetryCount()).isEqualTo(1);
        assertThat(v.isQuarantined()).isFalse();
    }

    @Test
    @DisplayName("R22: FAILED에서 재시도 — startProbing()으로 PROBING에 재진입한다")
    void retryReentersProbingFromFailed() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();
        v.fail("일시 오류");
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.FAILED);

        v.startProbing();

        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.PROBING);
    }

    @Test
    @DisplayName("R22.2: 변환이 계속 실패하면 재시도는 3회에서 멈추고 QUARANTINED로 격리되며 FAILED와 이유가 저장된다")
    void thirdFailureQuarantines() {
        ShortVideo v = video();
        v.markUploaded();

        v.startProbing();
        v.fail("1차 실패");
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.FAILED);
        assertThat(v.isQuarantined()).isFalse();

        v.startProbing();
        v.fail("2차 실패");
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.FAILED);
        assertThat(v.isQuarantined()).isFalse();

        v.startProbing();
        v.fail("3차 실패");

        assertThat(v.getRetryCount()).isEqualTo(3);
        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.QUARANTINED);
        assertThat(v.isQuarantined()).isTrue();
        assertThat(v.getFailureReason()).isEqualTo("3차 실패");

        assertThatThrownBy(v::startProbing).isInstanceOf(ShortsException.class);
    }

    @Test
    @DisplayName("R22: 실패 사유 500자 초과분은 잘라 저장한다")
    void failureReasonIsTruncated() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();

        v.fail("x".repeat(1000));

        assertThat(v.getFailureReason()).hasSize(500);
    }

    @Test
    @DisplayName("R22: TRANSCODING에서도 실패하면 FAILED로 전이한다")
    void transcodingFailureTransitionsToFailed() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();
        v.startTranscoding();

        v.fail("변환기 오류");

        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.FAILED);
        assertThat(v.getRetryCount()).isEqualTo(1);
    }

    private static final TranscodeOutput COMPLETE_OUTPUT = new TranscodeOutput(
            "shorts/1/1080/master.m3u8", "shorts/1/720/master.m3u8", "shorts/1/480/master.m3u8",
            "shorts/1/master.m3u8", "shorts/1/thumb.jpg");

    @Test
    @DisplayName("R23.1: 변환이 완료되면 세 렌디션·마스터 플레이리스트·썸네일 경로가 모두 기록되고 READY로 전이한다")
    void completeTranscodingWithFullOutputReachesReady() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();
        v.startTranscoding();

        v.completeTranscoding(COMPLETE_OUTPUT);

        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.READY);
        assertThat(v.getRendition1080pPath()).isEqualTo("shorts/1/1080/master.m3u8");
        assertThat(v.getRendition720pPath()).isEqualTo("shorts/1/720/master.m3u8");
        assertThat(v.getRendition480pPath()).isEqualTo("shorts/1/480/master.m3u8");
        assertThat(v.getMasterPlaylistPath()).isEqualTo("shorts/1/master.m3u8");
        assertThat(v.getThumbnailPath()).isEqualTo("shorts/1/thumb.jpg");
    }

    @Test
    @DisplayName("R23 경계: 썸네일이 빠진 산출물은 READY로 가지 않고 FAILED와 사유를 남긴다")
    void completeTranscodingWithMissingThumbnailFailsInstead() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();
        v.startTranscoding();
        TranscodeOutput missingThumbnail = new TranscodeOutput(
                "shorts/1/1080/master.m3u8", "shorts/1/720/master.m3u8", "shorts/1/480/master.m3u8",
                "shorts/1/master.m3u8", null);

        v.completeTranscoding(missingThumbnail);

        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.FAILED);
        assertThat(v.getFailureReason()).contains("썸네일");
        assertThat(v.getRetryCount()).isEqualTo(1);
        assertThat(v.getThumbnailPath()).isNull();
    }

    @Test
    @DisplayName("R23.2: 480x854 렌디션 생성이 실패하면(경로 없음) READY가 되지 않고 FAILED와 이유가 기록된다")
    void completeTranscodingWithMissing480pRenditionFailsInstead() {
        ShortVideo v = video();
        v.markUploaded();
        v.startProbing();
        v.startTranscoding();
        TranscodeOutput missing480p = new TranscodeOutput(
                "shorts/1/1080/master.m3u8", "shorts/1/720/master.m3u8", null,
                "shorts/1/master.m3u8", "shorts/1/thumb.jpg");

        v.completeTranscoding(missing480p);

        assertThat(v.getStatus()).isEqualTo(ShortVideoStatus.FAILED);
        assertThat(v.getFailureReason()).contains("480p");
        assertThat(v.getRetryCount()).isEqualTo(1);
        assertThat(v.getRendition480pPath()).isNull();
    }
}
