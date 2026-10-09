package com.beomsu.becommerce.live.web;

import com.beomsu.becommerce.live.LiveBroadcastStatus;

/**
 * 비로그인 시청자가 재생에 필요한 최소 정보(R5 원칙, R9의 시청 화면이 쓴다). 판매자 전용
 * {@code LiveBroadcastView}와 달리 {@code streamKey}를 절대 담지 않는다 — 대신 이미
 * MediaMTX HLS 주소로 조립된 {@code hlsUrl}만 준다({@code LIVE}가 아니면 null).
 *
 * <p>{@code title}은 시청 화면이 "어떤 방송을 보고 있는지" 보여주려고 추가했다(기존
 * 필드는 그대로 두는 호환 추가라 응답 모양은 깨지지 않는다).
 */
public record LivePlaybackView(long id, LiveBroadcastStatus status, String hlsUrl, String title) {
}
