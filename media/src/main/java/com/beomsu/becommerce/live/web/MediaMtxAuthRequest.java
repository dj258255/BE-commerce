package com.beomsu.becommerce.live.web;

/**
 * MediaMTX HTTP 인증 훅({@code authHTTPAddress})이 보내는 요청(R2) — 실제로는 ip·user·
 * password·protocol·id·query 같은 필드도 더 있지만, 지금 쓰는 건 {@code path}·{@code action}
 * 둘뿐이라 나머지는 선언하지 않는다(Jackson 기본값이 알 수 없는 필드를 무시한다).
 */
public record MediaMtxAuthRequest(String path, String action) {
}
