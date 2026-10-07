package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** R21: 업로드 메타 검증 경계값(60초·200MB·9:16)을 확인한다. */
class UploadMetaTest {

    @Test
    @DisplayName("R21: 정상 메타는 그대로 보관된다")
    void validMetaIsAccepted() {
        UploadMeta meta = new UploadMeta(59, 100_000_000L, 1080, 1920, "video/mp4");
        assertThat(meta.durationSeconds()).isEqualTo(59);
        assertThat(meta.width()).isEqualTo(1080);
        assertThat(meta.height()).isEqualTo(1920);
    }

    @Test
    @DisplayName("R21 경계: 길이 정확히 60초는 허용된다")
    void exactlySixtySecondsIsAllowed() {
        UploadMeta meta = new UploadMeta(60, 1_000L, 9, 16, "video/mp4");
        assertThat(meta.durationSeconds()).isEqualTo(60);
    }

    @Test
    @DisplayName("R21 경계: 길이 61초는 거절된다")
    void sixtyOneSecondsIsRejected() {
        assertThatThrownBy(() -> new UploadMeta(61, 1_000L, 9, 16, "video/mp4"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DURATION");
    }

    @Test
    @DisplayName("R21 경계: 길이 0초 이하는 거절된다")
    void zeroOrNegativeDurationIsRejected() {
        assertThatThrownBy(() -> new UploadMeta(0, 1_000L, 9, 16, "video/mp4"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_DURATION");
    }

    @Test
    @DisplayName("R21 경계: 파일 크기 정확히 200MB는 허용된다")
    void exactlyTwoHundredMbIsAllowed() {
        UploadMeta meta = new UploadMeta(10, UploadMeta.MAX_FILE_SIZE_BYTES, 9, 16, "video/mp4");
        assertThat(meta.fileSizeBytes()).isEqualTo(UploadMeta.MAX_FILE_SIZE_BYTES);
    }

    @Test
    @DisplayName("R21 경계: 파일 크기 200MB+1바이트는 거절된다")
    void overTwoHundredMbIsRejected() {
        assertThatThrownBy(() -> new UploadMeta(10, UploadMeta.MAX_FILE_SIZE_BYTES + 1, 9, 16, "video/mp4"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_FILE_SIZE");
    }

    @Test
    @DisplayName("R21 경계: 파일 크기 0 이하는 거절된다")
    void zeroOrNegativeFileSizeIsRejected() {
        assertThatThrownBy(() -> new UploadMeta(10, 0L, 9, 16, "video/mp4"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_FILE_SIZE");
    }

    @Test
    @DisplayName("R21: 세로(9:16) 비율은 허용된다 — 1080x1920")
    void portraitAspectRatioIsAllowed() {
        UploadMeta meta = new UploadMeta(10, 1_000L, 1080, 1920, "video/mp4");
        assertThat(meta.width()).isEqualTo(1080);
    }

    @Test
    @DisplayName("R21: 가로(16:9) 비율은 거절된다")
    void landscapeAspectRatioIsRejected() {
        assertThatThrownBy(() -> new UploadMeta(10, 1_000L, 1920, 1080, "video/mp4"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ASPECT_RATIO");
    }

    @Test
    @DisplayName("R21: 정사각(1:1) 비율은 거절된다")
    void squareAspectRatioIsRejected() {
        assertThatThrownBy(() -> new UploadMeta(10, 1_000L, 1000, 1000, "video/mp4"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ASPECT_RATIO");
    }

    @Test
    @DisplayName("R21: 너비·높이가 0 이하이면 거절된다")
    void nonPositiveDimensionsAreRejected() {
        assertThatThrownBy(() -> new UploadMeta(10, 1_000L, 0, 1920, "video/mp4"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "INVALID_ASPECT_RATIO");
    }
}
