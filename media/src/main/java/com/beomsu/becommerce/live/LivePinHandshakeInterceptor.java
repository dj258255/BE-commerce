package com.beomsu.becommerce.live;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WebSocket 핸드셰이크에서 경로의 {@code {broadcastId}}를 뽑아 세션 속성에 심는다(R9).
 *
 * <p>{@code WebSocketHandlerRegistry.addHandler}는 경로를 패턴으로만 매칭하고 변수를
 * 자동으로 바인딩해 주지 않는다(MVC {@code @PathVariable}과 다르다) — 그래서 등록 경로는
 * {@code /pins/ws} 와일드카드(*)로 두고, 여기서 직접 숫자를 파싱한다.
 */
class LivePinHandshakeInterceptor implements HandshakeInterceptor {

    static final String BROADCAST_ID_ATTRIBUTE = "broadcastId";

    private static final Pattern BROADCAST_ID_PATTERN = Pattern.compile("/live/broadcasts/(\\d+)/pins/ws$");

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        Matcher matcher = BROADCAST_ID_PATTERN.matcher(request.getURI().getPath());
        if (!matcher.find()) {
            return false; // 경로에서 방송 id를 못 찾으면 핸드셰이크 자체를 거절한다
        }
        attributes.put(BROADCAST_ID_ATTRIBUTE, Long.parseLong(matcher.group(1)));
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // 할 일 없음 — beforeHandshake에서 이미 속성을 심었다.
    }
}
