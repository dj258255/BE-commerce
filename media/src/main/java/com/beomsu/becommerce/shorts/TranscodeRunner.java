package com.beomsu.becommerce.shorts;

/**
 * 변환(probe → transcode) 실행기 포트(R23) — 실제 FFmpeg 호출을 이 인터페이스 뒤로 숨긴다.
 *
 * <p>운영 빈은 {@code FfmpegTranscodeRunner}(ProcessBuilder로 실제 ffmpeg를 부른다,
 * docs/performance/shorts-transcode.md의 비교 결과를 따른다 — filtersplit·superfast)뿐이다.
 * 결정적인 가짜 구현({@code FakeTranscodeRunner})은 테스트 소스에만 있다 — 기본 게이트
 * ({@code ./gradlew -p commerce test})의 대부분이 실제 FFmpeg를 부르면 환경마다 결과가
 * 흔들리고 느려지기 때문이다. 실제 FFmpeg를 부르는 테스트는 ffmpeg가 있을 때만 돈다
 * ({@code FfmpegTranscodeRunnerTest}, 없으면 건너뜀).
 */
public interface TranscodeRunner {

    /** 메타 점검(probe) — 실제 파일이 선언한 메타(코덱·해상도 등)와 맞는지 확인한다. */
    ProbeResult probe(String objectKey);

    /** 변환 — 세 화질 HLS + 마스터 재생목록 + 썸네일을 만든다. */
    TranscodeResult transcode(String objectKey);

    /** probe 결과. 실패면 {@code failureReason}에 사유가 담긴다. */
    record ProbeResult(boolean success, String failureReason) {
        public static ProbeResult ok() {
            return new ProbeResult(true, null);
        }

        public static ProbeResult fail(String reason) {
            return new ProbeResult(false, reason);
        }
    }

    /** 변환 결과. 성공이면 {@code output}에 산출물이 담기고(완전성은 R23.2가 별도로 본다),
     * 실패면 {@code failureReason}에 사유가 담긴다. */
    record TranscodeResult(boolean success, String failureReason, TranscodeOutput output) {
        public static TranscodeResult ok(TranscodeOutput output) {
            return new TranscodeResult(true, null, output);
        }

        public static TranscodeResult fail(String reason) {
            return new TranscodeResult(false, reason, null);
        }
    }
}
