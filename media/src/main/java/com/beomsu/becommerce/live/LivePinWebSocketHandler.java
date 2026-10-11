package com.beomsu.becommerce.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 시청자 WebSocket 연결(R9) — {@code /api/v1/live/broadcasts/{id}/pins/ws}. 로그인 없이도
 * 붙을 수 있다(R5와 같은 "비로그인 시청 허용" 원칙 — SecurityConfig가 이 경로만 permitAll).
 *
 * <p>연결되는 순간 그 방송의 현재 고정 스냅샷을 보낸다(R9.2 — 최초 입장과 재연결을 구분하지
 * 않는다, 둘 다 "지금 상태를 모르는 채 붙는 것"은 같다). 이후 {@link LivePinService}가
 * {@link #broadcast}를 부르면 그 방송에 붙어 있는 모든 세션에 같은 메시지를 내보낸다.
 *
 * <p>세션 레지스트리를 이 클래스가 들고 있는 이유: 접속 관리(연결·해제)와 발행을 한 곳에 두면
 * "연결은 끊겼는데 발행 쪽은 몰라 계속 보내려 한다" 같은 불일치가 생기지 않는다.
 */
@Component
class LivePinWebSocketHandler extends TextWebSocketHandler implements LivePinBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(LivePinWebSocketHandler.class);

    private final Map<Long, Set<WebSocketSession>> sessionsByBroadcastId = new ConcurrentHashMap<>();
    private final LivePinSnapshotReader snapshotReader;
    private final ObjectMapper objectMapper;

    LivePinWebSocketHandler(LivePinSnapshotReader snapshotReader, ObjectMapper objectMapper) {
        this.snapshotReader = snapshotReader;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Long broadcastId = broadcastIdOf(session);
        if (broadcastId == null) {
            session.close(CloseStatus.BAD_DATA.withReason("경로에서 방송 id를 찾을 수 없습니다"));
            return;
        }
        sessionsByBroadcastId.computeIfAbsent(broadcastId, id -> ConcurrentHashMap.newKeySet()).add(session);
        send(session, snapshotReader.snapshot(broadcastId));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
        Long broadcastId = broadcastIdOf(session);
        if (broadcastId == null) {
            return;
        }
        Set<WebSocketSession> sessions = sessionsByBroadcastId.get(broadcastId);
        if (sessions != null) {
            sessions.remove(session);
        }
    }

    /** {@link LivePinService}가 고정·해제·가격 변경마다 부른다 — 그 방송에 붙은 세션 전부에게 보낸다. */
    @Override
    public void broadcast(LivePinEventView event) {
        Set<WebSocketSession> sessions = sessionsByBroadcastId.get(event.broadcastId());
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                send(session, event);
            }
        }
    }

    private void send(WebSocketSession session, LivePinEventView event) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (Exception e) {
            // 한 시청자에게 못 보냈다고 나머지 전송·방송 로직 전체를 막을 이유가 없다 — 그
            // 세션은 곧 afterConnectionClosed로 정리된다(끊긴 연결에 계속 쓰려다 난 에러인
            // 경우가 흔하다).
            log.warn("고정 이벤트 전송 실패 broadcastId={} seq={}", event.broadcastId(), event.seq(), e);
        }
    }

    private static Long broadcastIdOf(WebSocketSession session) {
        return (Long) session.getAttributes().get(LivePinHandshakeInterceptor.BROADCAST_ID_ATTRIBUTE);
    }
}
