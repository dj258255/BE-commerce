package com.beomsu.becommerce.live;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * 고정 이벤트 WebSocket 등록(R9) — {@code /api/v1/live/broadcasts/{id}/pins/ws}. 비로그인
 * 시청을 허용하므로({@code SecurityConfig}가 이 경로만 permitAll) Origin 검사는 끈다 — 이
 * 저장소의 다른 공개 읽기 엔드포인트(숏폼 피드·HLS 재생)와 같은 수준이다.
 */
@Configuration
@EnableWebSocket
class LivePinWebSocketConfig implements WebSocketConfigurer {

    private final LivePinWebSocketHandler handler;

    LivePinWebSocketConfig(LivePinWebSocketHandler handler) {
        this.handler = handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/api/v1/live/broadcasts/*/pins/ws")
                .addInterceptors(new LivePinHandshakeInterceptor())
                .setAllowedOrigins("*");
    }
}
