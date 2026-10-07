package com.beomsu.becommerce.shorts;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 결정적인 가짜 변환 실행기(R23) — 실제 FFmpeg를 부르지 않는다.
 *
 * <p>스크립트하지 않은 objectKey는 항상 완전한 성공 결과를 돌려준다(기본값). 테스트는
 * {@link #scriptProbe}·{@link #scriptTranscode}로 특정 objectKey의 결과(성공·실패·불완전
 * 산출물)를 미리 정해 둘 수 있다 — 같은 objectKey는 다시 지우기 전까지 항상 같은 결과를
 * 돌려준다(결정적). {@link #reset()}으로 테스트 간 스크립트가 새지 않게 한다.
 *
 * <p>실제 FFmpeg 구현이 붙기 전까지는 이 가짜가 유일한 {@link TranscodeRunner} 빈이다 — 항상
 * {@code @Component}로 등록되고, 변환 리스너({@link ShortsTranscodeListener})만 worker
 * 프로파일 게이트를 둔다.
 */
@Component
public class FakeTranscodeRunner implements TranscodeRunner {

    private final Map<String, ProbeResult> probeScript = new ConcurrentHashMap<>();
    private final Map<String, TranscodeResult> transcodeScript = new ConcurrentHashMap<>();

    @Override
    public ProbeResult probe(String objectKey) {
        return probeScript.getOrDefault(objectKey, ProbeResult.ok());
    }

    @Override
    public TranscodeResult transcode(String objectKey) {
        return transcodeScript.getOrDefault(objectKey, TranscodeResult.ok(defaultOutput(objectKey)));
    }

    /** 이 objectKey의 다음 {@link #probe} 호출 결과를 정해 둔다(테스트용). */
    public void scriptProbe(String objectKey, ProbeResult result) {
        probeScript.put(objectKey, result);
    }

    /** 이 objectKey의 다음 {@link #transcode} 호출 결과를 정해 둔다(테스트용). */
    public void scriptTranscode(String objectKey, TranscodeResult result) {
        transcodeScript.put(objectKey, result);
    }

    /** 다음 테스트가 이전 테스트의 스크립트를 보지 않게 한다. */
    public void reset() {
        probeScript.clear();
        transcodeScript.clear();
    }

    private static TranscodeOutput defaultOutput(String objectKey) {
        return new TranscodeOutput(
                objectKey + "/1080/master.m3u8",
                objectKey + "/720/master.m3u8",
                objectKey + "/480/master.m3u8",
                objectKey + "/master.m3u8",
                objectKey + "/thumb.jpg");
    }
}
