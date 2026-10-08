package com.beomsu.becommerce.live;

/** 고정 이벤트 종류(R9) — WebSocket으로 시청자에게 보내는 메시지의 {@code type}과 같다. */
public enum LivePinEventType {
    PINNED,
    UNPINNED,
    PRICE_CHANGED
}
