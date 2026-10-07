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
    @DisplayName("R22: 허용된 전이 — UPLOADING→UPLOADED→PROBING→TRANSCODING→READY")
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
    @DisplayName("R22 경계: 실패가 3회(maxRetries)에 도달하면 QUARANTINED로 격리되고 더는 재시도할 수 없다")
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
}
