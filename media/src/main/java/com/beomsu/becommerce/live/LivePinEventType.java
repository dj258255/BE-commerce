package com.beomsu.becommerce.live;

/** 고정 이벤트 종류(R9·R13·R14) — WebSocket으로 시청자에게 보내는 메시지의 {@code type}과 같다. */
public enum LivePinEventType {
    PINNED,
    UNPINNED,
    PRICE_CHANGED,

    /**
     * 한정 수량 선점·반환에 따른 남은 수량 갱신(R13·R14). {@code remainingQuantity}가 0이면
     * 매진이다 — 별도 "SOLD_OUT" 타입을 두지 않고 이 값으로 가른다(R13.1의 "남은 수량 갱신"과
     * R14.1의 "매진 전환"은 같은 신호의 두 표현일 뿐이다, ADR-085 R13·R14 절 참고).
     *
     * <p><b>중요: 이 이벤트는 R9의 {@code effectiveAt} 재생 동기화 게이트를 타지 않는다</b> —
     * 클라이언트는 받는 즉시 적용해야 한다(PINNED·PRICE_CHANGED와 다르다). R14가 "1초 안에"를
     * 요구하는데, 영상 재생 지연은 R6에서 최대 5초까지 허용되므로 effectiveAt까지 기다리면
     * 그 자체로 SLA를 못 지킨다. "가능 여부"는 안전 문제라 가격 표시의 "영상과 안 맞아
     * 보임" 문제보다 우선한다.
     */
    QUANTITY_CHANGED
}
