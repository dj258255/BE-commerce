package com.beomsu.becommerce.live;

import java.time.Instant;

/**
 * 라이브 방송 조회 응답(R1) — 방송주 본인에게만 나간다({@code LiveBroadcastService#get}이
 * 소유권을 먼저 확인한다, IDOR 방지). {@code streamKey}를 담으므로 공개 엔드포인트(피드 등)에는
 * 절대 쓰지 않는다 — 다음 단계(시청)가 생기면 키 없는 별도 뷰를 만든다.
 */
public record LiveBroadcastView(
        Long id,
        LiveBroadcastStatus status,
        String streamKey,
        String title,
        Instant createdAt,
        Instant startedAt,
        Instant endedAt) {

    static LiveBroadcastView withKey(LiveBroadcast broadcast) {
        return new LiveBroadcastView(broadcast.getId(), broadcast.getStatus(), broadcast.getStreamKey(),
                broadcast.getTitle(), broadcast.getCreatedAt(), broadcast.getStartedAt(), broadcast.getEndedAt());
    }
}
