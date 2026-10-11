package com.beomsu.becommerce.live;

/**
 * MediaMTX 경로(path) ↔ 방송 id 변환(R2·R3·R9).
 *
 * <p><b>경로에는 더 이상 스트림 키를 싣지 않는다</b>(보안 수정, R1.2·R5·R9 관련) — 예전에는
 * {@code live/{streamKey}}였고, 그 경로가 RTMP 송출 주소이자 HLS 시청 주소이기도 해서
 * 시청 화면(비로그인 포함 누구나 보는 페이지)에 스트림 키 원문이 그대로 노출됐다. 스트림
 * 키는 "이 방송에 대신 송출할 수 있는" 자격증명이라, 그게 새면 R1.2가 막으려는 바로 그
 * 위험(남이 방송에 끼어든다)이 시청자 전체로 열리는 셈이었다(ADR-084 "대가" 2026-10-09
 * 추가 절 참고).
 *
 * <p>지금은 경로가 {@code live/{broadcastId}}다 — 숫자 id는 시청 화면에 그대로 노출돼도
 * 안전하다(그 자체로는 아무 권한도 없다). 송출 인증(publish)에 필요한 비밀은 경로가 아니라
 * RTMP URL의 쿼리 문자열({@code ?pass=<스트림키>})로 따로 보내고, MediaMTX가 HTTP 인증 훅에
 * {@code password}(또는 원문 {@code query}) 필드로 실어 보낸다 — {@code
 * LiveMediaHooksController#auth}가 그 값을 방송의 실제 스트림 키와 맞댄다. MediaMTX 설정
 * ({@code mediamtx.yml}의 경로 패턴 {@code ~^live/.+$})은 그대로 숫자 id에도 맞는다.
 */
public final class LiveStreamPaths {

    public static final String PREFIX = "live/";

    private LiveStreamPaths() {
    }

    /** {@code path}("live/{id}")에서 방송 id를 뽑는다 — 접두사가 없거나 숫자가 아니면 null. */
    static Long broadcastIdFrom(String path) {
        if (path == null || !path.startsWith(PREFIX)) {
            return null;
        }
        String idPart = path.substring(PREFIX.length());
        try {
            return Long.parseLong(idPart);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** MediaMTX 경로로 쓸 문자열 — {@code "live/{id}"}. */
    public static String pathFor(long broadcastId) {
        return PREFIX + broadcastId;
    }
}
