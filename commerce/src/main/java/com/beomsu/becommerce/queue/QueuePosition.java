package com.beomsu.becommerce.queue;

/**
 * 대기열에서의 내 위치 스냅샷.
 *
 * @param eventId      대기열(이벤트) 식별자
 * @param position     1-based 순번(= rank + 1). 대기열에 없으면 {@code -1}
 * @param waitingAhead 내 앞에 서 있는 인원(= 0-based rank). 대기열에 없으면 {@code -1}
 * @param admitted     입장 여부. {@code rank < admitLimit}이면 true — 앞에서부터 admitLimit명만 입장
 * @param total        현재 대기열 전체 인원(ZCARD)
 */
public record QueuePosition(
        String eventId,
        long position,
        long waitingAhead,
        boolean admitted,
        long total) {

    /** 대기열에 없는 상태(입장/이탈 완료 또는 조회 불가). */
    static QueuePosition notInQueue(String eventId, long total) {
        return new QueuePosition(eventId, -1, -1, false, total);
    }
}
