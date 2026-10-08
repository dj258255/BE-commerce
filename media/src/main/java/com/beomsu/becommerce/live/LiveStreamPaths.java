package com.beomsu.becommerce.live;

/**
 * MediaMTX 경로(path) ↔ 스트림 키 변환(R2·R3).
 *
 * <p>RTMP 송출 주소는 {@code rtmp://<host>/live/{streamKey}} 형태를 쓴다 — MediaMTX 설정
 * ({@code mediamtx.yml}의 경로 패턴 {@code ~^live/.+$})과 여기 {@link #PREFIX}가 반드시
 * 같이 바뀌어야 한다. MediaMTX의 HTTP 인증 훅·{@code runOnReady}/{@code runOnNotReady}
 * 훅이 돌려주는 {@code path}(또는 {@code $MTX_PATH})가 "live/스트림키" 꼴로 온다.
 *
 * <p>{@link #PREFIX}는 public이다 — {@code live.web.LivePlaybackController}(R9, 공개
 * 재생 URL 조립)가 다른 패키지에서도 같은 접두사를 쓴다.
 */
public final class LiveStreamPaths {

    public static final String PREFIX = "live/";

    private LiveStreamPaths() {
    }

    /** {@code path}가 {@link #PREFIX}로 시작하지 않으면 null(이 방송 체계가 아닌 경로). */
    static String keyFrom(String path) {
        if (path == null || !path.startsWith(PREFIX)) {
            return null;
        }
        String key = path.substring(PREFIX.length());
        return key.isBlank() ? null : key;
    }
}
