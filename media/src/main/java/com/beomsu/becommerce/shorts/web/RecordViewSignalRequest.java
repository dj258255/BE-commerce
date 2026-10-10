package com.beomsu.becommerce.shorts.web;

/**
 * 시청 신호 기록 요청(R27). {@code anonymousId}는 로그인하지 않았을 때만 쓴다 — 로그인
 * 요청이면 서버가 principal의 userId를 쓰고 이 값은 무시한다(신원은 클라이언트가 못 정한다).
 */
public record RecordViewSignalRequest(int watchSeconds, boolean completed, int replayCount,
                                      boolean skippedWithin3s, boolean productTagTapped,
                                      String anonymousId) {
}
