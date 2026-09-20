package com.beomsu.becommerce.timeline;

import java.time.Instant;

/**
 * 타임라인의 한 줄. 어느 모듈에서 왔든 같은 모양으로 정규화한다.
 *
 * @param at      일어난 시각. 정렬 기준이다
 * @param source  어느 도메인에서 온 사실인가 (ORDER, PAYMENT, LEDGER ...)
 * @param event   무슨 일이 있었나 (짧은 식별자. 화면·검색에 쓴다)
 * @param summary 사람이 읽을 한 줄. <b>코드가 만든다.</b> 숫자를 여기서 계산하지 않는다
 * @param amount  대표 금액이 있으면 원 단위. 없으면 null (모든 사건에 금액이 있진 않다)
 * @param figures 요약 문장이 <b>말하는</b> 금액 전부. 대사처럼 한 줄에 둘이 나오는 경우가 있다
 *                (내부 10,000 / 외부 9,730). 이 목록이 없으면 읽는 쪽이 문장을 정규식으로 다시
 *                파싱해야 하고, 요약 문구를 고칠 때마다 그 파싱이 조용히 깨진다
 * @param mentionedDates 요약 문장이 말하는 날짜. {@code at} 은 <b>사건이 일어난 시각</b>이라
 *                문장이 가리키는 다른 날짜(자동해제 예정일 등)는 여기 담는다
 */
public record TimelineEntry(Instant at, Source source, String event, String summary, Long amount,
                            java.util.List<Long> figures,
                            java.util.List<java.time.LocalDate> mentionedDates) {

    public TimelineEntry {
        figures = figures == null ? java.util.List.of() : java.util.List.copyOf(figures);
        mentionedDates = mentionedDates == null
                ? java.util.List.of() : java.util.List.copyOf(mentionedDates);
    }

    /** 사실의 출처 도메인. 화면에서 색으로 구분하고, 필터에도 쓴다. */
    public enum Source {
        ORDER, PAYMENT, LEDGER, ESCROW, SETTLEMENT,
        POINT, WALLET, DISPUTE, RECONCILIATION, COMPENSATION, AUDIT
    }

    public static TimelineEntry of(Instant at, Source source, String event, String summary) {
        return new TimelineEntry(at, source, event, summary, null,
                java.util.List.of(), java.util.List.of());
    }

    public static TimelineEntry of(Instant at, Source source, String event, String summary, long amount) {
        return new TimelineEntry(at, source, event, summary, amount,
                java.util.List.of(amount), java.util.List.of());
    }

    /** 요약이 금액을 여럿 말할 때 — 대사처럼 한 줄에 내부·외부가 같이 나오는 경우. */
    public static TimelineEntry of(Instant at, Source source, String event, String summary,
                                   long amount, java.util.List<Long> figures,
                                   java.util.List<java.time.LocalDate> mentionedDates) {
        return new TimelineEntry(at, source, event, summary, amount, figures, mentionedDates);
    }

    /** 요약이 말하는 날짜가 {@code at} 말고 따로 있을 때. */
    public static TimelineEntry of(Instant at, Source source, String event, String summary,
                                   long amount, java.time.LocalDate mentioned) {
        return new TimelineEntry(at, source, event, summary, amount,
                java.util.List.of(amount), java.util.List.of(mentioned));
    }
}
