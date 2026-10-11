package com.beomsu.becommerce.shorts;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 실제 FFmpeg를 {@link ProcessBuilder}로 부르는 변환 실행기(R23 3단계).
 *
 * <p>방식은 docs/performance/shorts-transcode.md의 비교 결과를 따른다 — 입력을 한 번만
 * 디코드하고({@code -filter_complex split=3}) 세 화질(1080x1920·720x1280·480x854)을 한
 * ffmpeg 프로세스가 동시에 출력하는 {@code filtersplit} 방식이 가장 빨랐다. 기본 프리셋도 그
 * 비교에서 가장 빨랐던 {@code superfast}다. ffmpeg 경로·프리셋·시간 제한은
 * {@code app.shorts.transcode.ffmpeg.*}로 바꿀 수 있다(ADR-081).
 *
 * <p>{@code objectKey}는 {@code LocalFileShortsStorage}와 같은 {@code app.shorts.storage.base-dir}
 * 아래의 상대 경로로 푼다(R21 로컬 저장소 전제) — 운영에서 MinIO로 바뀌면 이 가정도 다시 본다.
 * 산출물 경로({@link TranscodeOutput})도 같은 base-dir 기준 상대 경로로 돌려준다 — 절대
 * 경로를 DB에 남기지 않는다.
 *
 * <p><b>알려진 한계</b>: 입력에 오디오 트랙이 전혀 없으면({@code -map 0:a}가 가리킬 스트림이
 * 없음) ffmpeg가 실패한다 — 지금 요구사항(R21 업로드 제약)에 없는 경우라 다루지 않는다.
 */
@Component
class FfmpegTranscodeRunner implements TranscodeRunner {

    private static final int[] LABELS = {1080, 720, 480};
    private static final String[] SCALES = {"1080:1920", "720:1280", "480:854"};
    private static final String[] BITRATES = {"5M", "2.5M", "1M"};
    private static final String[] BANDWIDTHS = {"5000000", "2500000", "1000000"};
    private static final int STDERR_TAIL_CHARS = 1000;

    private final Path baseDir;
    private final String ffmpegBin;
    private final String preset;
    private final Duration timeout;

    FfmpegTranscodeRunner(
            @Value("${app.shorts.storage.base-dir:${java.io.tmpdir}/becommerce-shorts}") String baseDir,
            @Value("${app.shorts.transcode.ffmpeg.bin:ffmpeg}") String ffmpegBin,
            @Value("${app.shorts.transcode.ffmpeg.preset:superfast}") String preset,
            @Value("${app.shorts.transcode.ffmpeg.timeout-seconds:120}") long timeoutSeconds) {
        this.baseDir = Path.of(baseDir);
        this.ffmpegBin = ffmpegBin;
        this.preset = preset;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
    }

    @Override
    public ProbeResult probe(String objectKey) {
        Path input = baseDir.resolve(objectKey);
        if (!Files.exists(input)) {
            return ProbeResult.fail("원본 파일을 찾을 수 없습니다: " + objectKey);
        }
        // 전체를 디코드만 해 보고 버린다(-f null -) — 코덱이 실제로 열리는지 확인하는 것이
        // probe 단계의 역할이다(길이·크기·세로 비율은 업로드 시작 때 UploadMeta가 이미 봤다).
        ProcessOutcome outcome = run(List.of(
                ffmpegBin, "-hide_banner", "-loglevel", "error", "-i", input.toString(), "-f", "null", "-"));
        return outcome.success() ? ProbeResult.ok() : ProbeResult.fail(outcome.failureReason());
    }

    @Override
    public TranscodeResult transcode(String objectKey) {
        Path input = baseDir.resolve(objectKey);
        if (!Files.exists(input)) {
            return TranscodeResult.fail("원본 파일을 찾을 수 없습니다: " + objectKey);
        }

        String outKey = objectKey + ".out";
        Path outDir = baseDir.resolve(outKey);
        try {
            for (int label : LABELS) {
                Files.createDirectories(outDir.resolve(String.valueOf(label)));
            }
        } catch (IOException e) {
            return TranscodeResult.fail("출력 디렉터리를 만들 수 없습니다: " + e.getMessage());
        }

        ProcessOutcome renditions = run(renditionCommand(input, outDir));
        if (!renditions.success()) {
            return TranscodeResult.fail(renditions.failureReason());
        }

        ProcessOutcome thumbnail = run(thumbnailCommand(input, outDir));
        if (!thumbnail.success()) {
            return TranscodeResult.fail(thumbnail.failureReason());
        }

        try {
            writeMasterPlaylist(outDir);
        } catch (IOException e) {
            return TranscodeResult.fail("마스터 재생목록을 쓸 수 없습니다: " + e.getMessage());
        }

        TranscodeOutput output = new TranscodeOutput(
                outKey + "/1080/out.m3u8",
                outKey + "/720/out.m3u8",
                outKey + "/480/out.m3u8",
                outKey + "/master.m3u8",
                outKey + "/thumb.jpg");
        return TranscodeResult.ok(output);
    }

    /** 입력을 한 번만 디코드하고(split=3) 세 화질을 한 프로세스에서 동시에 HLS로 뽑는다. */
    private List<String> renditionCommand(Path input, Path outDir) {
        return List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y", "-i", input.toString(),
                "-filter_complex",
                "[0:v]split=3[v1][v2][v3];[v1]scale=" + SCALES[0] + "[v1o];[v2]scale=" + SCALES[1]
                        + "[v2o];[v3]scale=" + SCALES[2] + "[v3o]",
                "-map", "[v1o]", "-map", "0:a", "-c:v", "libx264", "-preset", preset,
                "-b:v", BITRATES[0], "-maxrate", BITRATES[0], "-bufsize", BITRATES[0], "-g", "60",
                "-c:a", "aac", "-b:a", "128k",
                "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
                outDir.resolve(String.valueOf(LABELS[0])).resolve("out.m3u8").toString(),
                "-map", "[v2o]", "-map", "0:a", "-c:v", "libx264", "-preset", preset,
                "-b:v", BITRATES[1], "-maxrate", BITRATES[1], "-bufsize", BITRATES[1], "-g", "60",
                "-c:a", "aac", "-b:a", "128k",
                "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
                outDir.resolve(String.valueOf(LABELS[1])).resolve("out.m3u8").toString(),
                "-map", "[v3o]", "-map", "0:a", "-c:v", "libx264", "-preset", preset,
                "-b:v", BITRATES[2], "-maxrate", BITRATES[2], "-bufsize", BITRATES[2], "-g", "60",
                "-c:a", "aac", "-b:a", "128k",
                "-f", "hls", "-hls_time", "4", "-hls_playlist_type", "vod", "-hls_segment_type", "fmp4",
                outDir.resolve(String.valueOf(LABELS[2])).resolve("out.m3u8").toString());
    }

    private List<String> thumbnailCommand(Path input, Path outDir) {
        return List.of(ffmpegBin, "-hide_banner", "-loglevel", "error", "-y", "-i", input.toString(),
                "-vframes", "1", "-vf", "scale=480:-1", outDir.resolve("thumb.jpg").toString());
    }

    /** R23 인수 조건 — 세 렌디션을 해상도·대역폭과 함께 참조하는 마스터 재생목록. */
    private void writeMasterPlaylist(Path outDir) throws IOException {
        StringBuilder sb = new StringBuilder("#EXTM3U\n#EXT-X-VERSION:7\n");
        for (int i = 0; i < LABELS.length; i++) {
            sb.append("#EXT-X-STREAM-INF:BANDWIDTH=").append(BANDWIDTHS[i])
                    .append(",RESOLUTION=").append(SCALES[i].replace(':', 'x')).append('\n')
                    .append(LABELS[i]).append("/out.m3u8\n");
        }
        Files.writeString(outDir.resolve("master.m3u8"), sb.toString());
    }

    private ProcessOutcome run(List<String> command) {
        Path stderrFile;
        try {
            stderrFile = Files.createTempFile("ffmpeg-stderr-", ".log");
        } catch (IOException e) {
            return ProcessOutcome.fail("임시 로그 파일을 만들 수 없습니다: " + e.getMessage());
        }
        try {
            Process process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(stderrFile.toFile())
                    .start();
            boolean finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return ProcessOutcome.fail("FFmpeg 시간 제한(" + timeout.toSeconds() + "초) 초과: "
                        + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                return ProcessOutcome.fail(tail(stderrFile));
            }
            return ProcessOutcome.ok();
        } catch (IOException e) {
            return ProcessOutcome.fail("FFmpeg 실행 실패: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ProcessOutcome.fail("FFmpeg 대기 중 인터럽트됨");
        } finally {
            try {
                Files.deleteIfExists(stderrFile);
            } catch (IOException ignored) {
                // 로그 임시 파일 정리 실패는 변환 결과에 영향을 주지 않는다.
            }
        }
    }

    private static String tail(Path stderrFile) {
        try {
            String content = Files.readString(stderrFile, StandardCharsets.UTF_8);
            return content.length() > STDERR_TAIL_CHARS
                    ? content.substring(content.length() - STDERR_TAIL_CHARS)
                    : content;
        } catch (IOException e) {
            return "FFmpeg가 0이 아닌 코드로 끝났고 stderr 로그도 읽을 수 없습니다: " + e.getMessage();
        }
    }

    private record ProcessOutcome(boolean success, String failureReason) {
        static ProcessOutcome ok() {
            return new ProcessOutcome(true, null);
        }

        static ProcessOutcome fail(String reason) {
            return new ProcessOutcome(false, reason);
        }
    }
}
