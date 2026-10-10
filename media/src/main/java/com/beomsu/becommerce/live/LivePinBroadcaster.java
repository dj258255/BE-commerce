package com.beomsu.becommerce.live;

/**
 * 고정 이벤트를 그 방송을 보고 있는 모든 시청자에게 내보내는 경계(R9) — 실제 구현
 * ({@code LivePinWebSocketHandler})은 WebSocket 세션을 들고 있지만, {@link LivePinService}는
 * 그 사실을 몰라도 된다. 테스트는 이 인터페이스의 가짜로 바꿔 실제 WebSocket 없이 "이벤트가
 * 몇 번·어떤 내용으로 나갔는가"만 본다.
 */
interface LivePinBroadcaster {

    void broadcast(LivePinEventView event);
}
