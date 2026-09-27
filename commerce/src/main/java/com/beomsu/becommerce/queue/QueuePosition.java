package com.beomsu.becommerce.queue;

/**
 * 대기열에서의 내 위치 스냅샷.
 *
 * @param eventId      대기열(이벤트) 식별자
 * @param position     1-based 순번(= rank + 1). 대기열에 없으면 {@code -1}
 * @param waitingAhead 내 앞에 서 있는 인원(= 0-based rank). 대기열에 없으면 {@code -1}
 * @param admitted     입장 여부. {@code rank < admitLimit}이고 매진이 아니면 true
 * @param total        현재 대기열 전체 인원(ZCARD)
 * @param soldOut      매진으로 막혀 입장하지 못했는지(#385). rank가 admitLimit 안이었지만 게이트 상품이
 *                     진짜로 매진이라 입장권을 받지 못했으면 true — 클라이언트는 더 기다리지 않고
 *                     매진을 알릴 수 있다. rank가 admitLimit 밖이면(아직 매진을 볼 자리가 아니면) false다.
 */
public record QueuePosition(
        String eventId,
        long position,
        long waitingAhead,
        boolean admitted,
        long total,
        boolean soldOut) {

    /** 대기열에 없는 상태(입장/이탈 완료 또는 조회 불가). */
    static QueuePosition notInQueue(String eventId, long total) {
        return new QueuePosition(eventId, -1, -1, false, total, false);
    }
}
