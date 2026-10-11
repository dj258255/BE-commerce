package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R26: 숏폼 변환 산출물을 디스크에서 찾아 주는 {@link ShortsMediaService}의 경로 조작 방어와
 * READY 게이트를 검증한다. 실제 ffmpeg·DB 없이 가짜 저장소 디렉터리({@code @TempDir})와
 * Mockito 리포지토리로 돈다.
 */
class ShortsMediaServiceTest {

    private static final UploadMeta VALID_META = new UploadMeta(10, 1_000_000L, 1080, 1920, "video/mp4");
    private static final long VIDEO_ID = 7L;

    @TempDir
    Path baseDir;

    private ShortVideoRepository repository;
    private ShortsMediaService mediaService;
    private Path mediaRoot;

    @BeforeEach
    void setUp() throws IOException {
        repository = mock(ShortVideoRepository.class);
        mediaService = new ShortsMediaService(repository, baseDir.toString());

        // 바깥(가짜 "탈출 대상")에 아무도 가리키면 안 되는 파일을 둔다 — 경로 조작 테스트의 표적.
        Files.writeString(baseDir.resolve("secret.txt"), "비밀");

        mediaRoot = baseDir.resolve("shorts/1/video7.out");
        Files.createDirectories(mediaRoot.resolve("1080"));
        Files.writeString(mediaRoot.resolve("master.m3u8"), "#EXTM3U\n");
        Files.writeString(mediaRoot.resolve("thumb.jpg"), "jpg");
        Files.writeString(mediaRoot.resolve("1080/out.m3u8"), "#EXTM3U\n#EXTINF:4,\nout0.m4s\n");
    }

    private ShortVideo readyVideo() {
        ShortVideo video = ShortVideo.upload(1L, "shorts/1/video7", VALID_META);
        video.markUploaded();
        video.startProbing();
        video.startTranscoding();
        video.completeTranscoding(new TranscodeOutput(
                "shorts/1/video7.out/1080/out.m3u8", "shorts/1/video7.out/720/out.m3u8",
                "shorts/1/video7.out/480/out.m3u8", "shorts/1/video7.out/master.m3u8",
                "shorts/1/video7.out/thumb.jpg"));
        return video;
    }

    @Test
    @DisplayName("R26: READY 영상의 마스터 재생목록·썸네일·렌디션 경로를 그대로 찾아낸다")
    void resolvesFilesInsideMediaRoot() {
        when(repository.findById(VIDEO_ID)).thenReturn(Optional.of(readyVideo()));

        assertThat(mediaService.resolve(VIDEO_ID, "master.m3u8")).isEqualTo(mediaRoot.resolve("master.m3u8"));
        assertThat(mediaService.resolve(VIDEO_ID, "thumb.jpg")).isEqualTo(mediaRoot.resolve("thumb.jpg"));
        assertThat(mediaService.resolve(VIDEO_ID, "1080/out.m3u8"))
                .isEqualTo(mediaRoot.resolve("1080/out.m3u8"));
    }

    @Test
    @DisplayName("R26 경계: ../로 미디어 루트를 벗어나려는 경로는 막힌다(찾을 수 없음)")
    void pathTraversalOutsideMediaRootIsRejected() {
        when(repository.findById(VIDEO_ID)).thenReturn(Optional.of(readyVideo()));

        assertThatThrownBy(() -> mediaService.resolve(VIDEO_ID, "../../../secret.txt"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "SHORT_VIDEO_NOT_FOUND");
    }

    @Test
    @DisplayName("R26 경계: 절대 경로를 넣어도(흡수 시도) 미디어 루트 밖은 못 본다")
    void absolutePathInjectionIsRejected() {
        when(repository.findById(VIDEO_ID)).thenReturn(Optional.of(readyVideo()));

        assertThatThrownBy(() -> mediaService.resolve(VIDEO_ID, baseDir.resolve("secret.txt").toString()))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "SHORT_VIDEO_NOT_FOUND");
    }

    @Test
    @DisplayName("R26 경계: READY가 아닌 영상은 산출물이 실제로 있어도 찾을 수 없다")
    void nonReadyVideoIsNotFoundEvenIfFilesExist() {
        ShortVideo uploading = ShortVideo.upload(1L, "shorts/1/video7", VALID_META);
        when(repository.findById(VIDEO_ID)).thenReturn(Optional.of(uploading));

        assertThatThrownBy(() -> mediaService.resolve(VIDEO_ID, "master.m3u8"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "SHORT_VIDEO_NOT_FOUND");
    }

    @Test
    @DisplayName("R26 경계: 존재하지 않는 파일명은 찾을 수 없다")
    void missingFileIsNotFound() {
        when(repository.findById(VIDEO_ID)).thenReturn(Optional.of(readyVideo()));

        assertThatThrownBy(() -> mediaService.resolve(VIDEO_ID, "720/out.m3u8"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "SHORT_VIDEO_NOT_FOUND");
    }

    @Test
    @DisplayName("R26 경계: 존재하지 않는 영상 id는 찾을 수 없다")
    void unknownVideoIdIsNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> mediaService.resolve(999L, "master.m3u8"))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "SHORT_VIDEO_NOT_FOUND");
    }
}
