package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * R23: 실제 FFmpeg를 {@link ProcessBuilder}로 불러 5초 세로 합성 영상을 세 화질 HLS +
 * 마스터 재생목록 + 썸네일로 바꾸는지 끝까지 확인한다({@link FfmpegTranscodeRunner}).
 *
 * <p>FFmpeg가 PATH에 없는 환경에서는 건너뛴다(이 테스트가 실제 외부 바이너리를 전제하는
 * 유일한 테스트다 — 기본 게이트의 나머지는 가짜 실행기로 결정적으로 돈다). 이 샌드박스는
 * studio.yaml의 {@code commerce.systemPackages: [ffmpeg]}로 설치돼 있어 실제로 돈다.
 */
class FfmpegTranscodeRunnerTest {

    private static final String OBJECT_KEY = "shorts/1/synthetic.mp4";

    @TempDir
    Path baseDir;

    @BeforeEach
    void skipWithoutFfmpeg() {
        assumeTrue(ffmpegAvailable(), "ffmpeg를 찾을 수 없어 이 테스트를 건너뜁니다");
    }

    @Test
    @DisplayName("R23: 5초 세로 합성 영상을 변환하면 READY에 필요한 산출물(세 렌디션·마스터·썸네일)이 전부 생긴다")
    void transcodesSyntheticVideoIntoCompleteOutput() throws IOException, InterruptedException {
        createSyntheticVideo(baseDir.resolve(OBJECT_KEY));
        FfmpegTranscodeRunner runner = new FfmpegTranscodeRunner(baseDir.toString(), "ffmpeg", "superfast", 60);

        TranscodeRunner.ProbeResult probe = runner.probe(OBJECT_KEY);
        assertThat(probe.success()).as("probe 실패 사유: %s", probe.failureReason()).isTrue();

        TranscodeRunner.TranscodeResult result = runner.transcode(OBJECT_KEY);
        assertThat(result.success()).as("transcode 실패 사유: %s", result.failureReason()).isTrue();

        TranscodeOutput output = result.output();
        assertThat(output.missingField()).isNull();
        assertRenditionHasSegments(output.rendition1080pPath());
        assertRenditionHasSegments(output.rendition720pPath());
        assertRenditionHasSegments(output.rendition480pPath());

        Path master = baseDir.resolve(output.masterPlaylistPath());
        assertThat(master).exists();
        String masterContent = Files.readString(master);
        assertThat(masterContent).contains("1080/out.m3u8", "720/out.m3u8", "480/out.m3u8", "RESOLUTION=", "BANDWIDTH=");

        Path thumbnail = baseDir.resolve(output.thumbnailPath());
        assertThat(thumbnail).isNotEmptyFile();
    }

    @Test
    @DisplayName("R23 경계: 존재하지 않는 원본 파일을 probe하면 성공하지 않고 실패 사유를 남긴다")
    void probingMissingFileFails() {
        FfmpegTranscodeRunner runner = new FfmpegTranscodeRunner(baseDir.toString(), "ffmpeg", "superfast", 60);

        TranscodeRunner.ProbeResult probe = runner.probe("shorts/1/does-not-exist.mp4");

        assertThat(probe.success()).isFalse();
        assertThat(probe.failureReason()).isNotBlank();
    }

    private void assertRenditionHasSegments(String relativePath) throws IOException {
        Path playlist = baseDir.resolve(relativePath);
        assertThat(playlist).exists();
        String content = Files.readString(playlist);
        assertThat(content).contains("#EXTM3U");
        boolean hasSegmentReference = content.lines().anyMatch(line -> !line.startsWith("#") && !line.isBlank());
        assertThat(hasSegmentReference).as("세그먼트 참조 없음: %s", playlist).isTrue();
    }

    /** 60초 R21 상한과 같은 모양(세로 9:16)이되 테스트를 빠르게 하려고 5초짜리로 만든다. */
    private void createSyntheticVideo(Path target) throws IOException, InterruptedException {
        Files.createDirectories(target.getParent());
        List<String> command = List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=1080x1920:rate=30:duration=5",
                "-f", "lavfi", "-i", "anullsrc=r=44100:cl=stereo",
                "-shortest", "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", "-b:a", "128k",
                target.toString());
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        boolean finished = process.waitFor(60, TimeUnit.SECONDS);
        if (!finished || process.exitValue() != 0) {
            throw new IllegalStateException("테스트용 합성 영상을 만들지 못했습니다(ffmpeg 종료 코드 확인 필요)");
        }
    }

    private static boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
