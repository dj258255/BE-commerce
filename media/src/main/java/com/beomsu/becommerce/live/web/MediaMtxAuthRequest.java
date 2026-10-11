package com.beomsu.becommerce.live.web;

/**
 * MediaMTX HTTP 인증 훅({@code authHTTPAddress})이 보내는 요청(R2). 실제로는 {@code ip}·
 * {@code protocol}·{@code id} 필드도 더 있지만 쓰지 않아 선언하지 않는다(Jackson 기본값이
 * 알 수 없는 필드를 무시한다).
 *
 * <p>{@code password}(보안 수정, ADR-084) — RTMP 송출 URL의 {@code ?pass=} 쿼리 값을
 * MediaMTX가 여기로 옮겨 싣는다. 송출 인증에 쓰는 비밀은 더 이상 {@code path}에 없다 — 경로는
 * 방송 공개 id({@code "live/42"})뿐이라 시청 화면에 노출돼도 안전하고, 비밀은 이 필드로만
 * 온다. 일부 설정에서는 이 필드가 비어 있고 원문 {@code query}에만 실릴 수 있어 그것도
 * 같이 받는다(컨트롤러가 둘 다 본다, 방어적).
 */
public record MediaMtxAuthRequest(String path, String action, String password, String query) {
}
