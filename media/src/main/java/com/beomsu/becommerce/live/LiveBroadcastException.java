package com.beomsu.becommerce.live;

import com.beomsu.becommerce.shared.DomainException;

/** 라이브 방송 도메인 예외(R1·R2·R3). code는 10-API-스펙 문서의 에러 코드 체계에 맞춘다. */
public class LiveBroadcastException extends DomainException {

    public LiveBroadcastException(String code, String message) {
        super(code, message);
    }

    /** R1.1: 방송 제목 없이 생성을 시도함 — 400(GlobalExceptionHandler의 기본값). */
    public static LiveBroadcastException invalidTitle() {
        return new LiveBroadcastException("INVALID_TITLE", "방송 제목은 필수입니다.");
    }

    public static LiveBroadcastException notFound(Object idOrKey) {
        return new LiveBroadcastException("LIVE_BROADCAST_NOT_FOUND", "방송을 찾을 수 없습니다: " + idOrKey);
    }

    /** 방송주 본인이 아닌 접근 — IDOR 방지(R1: "다른 판매자는 키를 조회할 수 없다"). */
    public static LiveBroadcastException forbidden(long id) {
        return new LiveBroadcastException("LIVE_BROADCAST_FORBIDDEN", "이 방송에 대한 권한이 없습니다: " + id);
    }

    /** {@code ShortsException.invalidTransition}과 같은 코드(409) — 허용되지 않은 상태 전이. */
    public static LiveBroadcastException invalidTransition(LiveBroadcastStatus from, LiveBroadcastStatus to) {
        return new LiveBroadcastException("INVALID_STATE_TRANSITION",
                "허용되지 않은 방송 상태 전이입니다: %s → %s".formatted(from, to));
    }
}
