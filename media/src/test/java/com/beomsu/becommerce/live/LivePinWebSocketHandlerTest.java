package com.beomsu.becommerce.live;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R9.2: {@link LivePinWebSocketHandler}가 연결 즉시 스냅샷을 보내는지, 방송별로 발행이
 * 섞이지 않는지를 가짜 {@link WebSocketSession}으로 검증한다(실제 네트워크 없음).
 */
class LivePinWebSocketHandlerTest {

    /** 운영 빈(Spring Boot 자동 구성)은 JavaTimeModule이 이미 등록돼 있다 — 테스트는 직접 만드니 맞춰 둔다. */
    private static ObjectMapper jsonMapper() {
        return new ObjectMapper().registerModule(new JavaTimeModule());
    }

    private static WebSocketSession sessionForBroadcast(long broadcastId) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getAttributes()).thenReturn(Map.of(LivePinHandshakeInterceptor.BROADCAST_ID_ATTRIBUTE, broadcastId));
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    @Test
    @DisplayName("R9.2: 연결하는 순간 그 방송의 현재 스냅샷을 즉시 받는다")
    void connectingSendsCurrentSnapshotImmediately() throws Exception {
        LivePinSnapshotReader snapshotReader = mock(LivePinSnapshotReader.class);
        LivePinEventView snapshot = new LivePinEventView(5L, LivePinEventType.PINNED, 1L, "P1", 9_900L, 50, 3L,
                Instant.parse("2026-01-01T00:00:00Z"));
        when(snapshotReader.snapshot(5L)).thenReturn(snapshot);
        LivePinWebSocketHandler handler = new LivePinWebSocketHandler(snapshotReader, jsonMapper());
        WebSocketSession session = sessionForBroadcast(5L);

        handler.afterConnectionEstablished(session);

        verify(session).sendMessage(argThat((TextMessage m) -> m.getPayload().contains("\"seq\":3")));
    }

    @Test
    @DisplayName("방송이 다르면 발행이 섞이지 않는다 — 방송 1에 보낸 이벤트는 방송 2의 세션에 안 간다")
    void broadcastDoesNotLeakAcrossBroadcasts() throws Exception {
        LivePinSnapshotReader snapshotReader = mock(LivePinSnapshotReader.class);
        when(snapshotReader.snapshot(anyLong()))
                .thenReturn(new LivePinEventView(1L, LivePinEventType.UNPINNED, null, null, null, null, 0,
                        Instant.parse("2026-01-01T00:00:00Z")));
        LivePinWebSocketHandler handler = new LivePinWebSocketHandler(snapshotReader, jsonMapper());
        WebSocketSession session1 = sessionForBroadcast(1L);
        WebSocketSession session2 = sessionForBroadcast(2L);
        handler.afterConnectionEstablished(session1);
        handler.afterConnectionEstablished(session2);
        org.mockito.Mockito.clearInvocations(session1, session2); // 연결 시 스냅샷 전송은 지금 관심사가 아니다

        handler.broadcast(new LivePinEventView(1L, LivePinEventType.PINNED, 100L, "P1", 9_900L, 50, 1L,
                Instant.parse("2026-01-01T00:00:01Z")));

        verify(session1).sendMessage(any(TextMessage.class));
        verify(session2, never()).sendMessage(any(TextMessage.class));
    }
}
