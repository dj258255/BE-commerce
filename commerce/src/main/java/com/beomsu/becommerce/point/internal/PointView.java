package com.beomsu.becommerce.point.internal;

import java.util.List;

/**
 * 포인트 조회 뷰 — 잔액 + 최근 이력. 엔티티 대신 노출한다.
 */
public record PointView(long balance, List<HistoryView> history) {

    /** 이력 1건 — 유형(EARN/USE/RESTORE/REFUND)·금액·관련 주문번호. */
    public record HistoryView(String type, long amount, String orderNo) {
        static HistoryView from(PointHistory h) {
            return new HistoryView(h.getType().name(), h.getAmount(), h.getOrderNo());
        }
    }

    public static PointView of(long balance, List<PointHistory> history) {
        return new PointView(balance, history.stream().map(HistoryView::from).toList());
    }
}
