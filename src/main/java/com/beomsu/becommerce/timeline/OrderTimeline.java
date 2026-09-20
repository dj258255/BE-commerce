package com.beomsu.becommerce.timeline;

import java.util.List;

/**
 * 조립 결과.
 *
 * @param orderNo         조회한 주문
 * @param entries         시간순 사실들
 * @param unavailable     조회에 실패한 출처들. <b>비어 있지 않으면 이 타임라인은 불완전하다.</b>
 *                        화면과 사람이 이걸 반드시 보게 해야 한다 — 조용히 빠지면
 *                        "그 도메인에 기록이 없다"로 잘못 읽힌다
 */
public record OrderTimeline(String orderNo, List<TimelineEntry> entries, List<String> unavailable) {

    /** 이 타임라인만 보고 판단해도 되는가. false면 빠진 곳이 있다. */
    public boolean complete() {
        return unavailable.isEmpty();
    }
}
