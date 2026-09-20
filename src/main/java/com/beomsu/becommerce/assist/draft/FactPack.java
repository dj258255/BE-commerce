package com.beomsu.becommerce.assist.draft;

import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.reconciliation.CauseSuggestion;
import com.beomsu.becommerce.timeline.OrderTimeline;
import com.beomsu.becommerce.timeline.TimelineEntry;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 초안이 쓸 수 있는 <b>사실의 전부</b>. 여기 없는 숫자는 초안에 나오면 안 된다.
 *
 * <p>이게 단순한 DTO가 아니라 <b>계약</b>인 이유: {@link NumericProvenanceGuard}가 초안에서 뽑은
 * 숫자를 이 목록과 대조해 하나라도 없으면 초안을 버린다. 즉 이 레코드가
 * "모델에게 허용된 어휘"를 정의한다.
 *
 * @param orderNo   주문번호. 문장에 그대로 나가도 되는 유일한 식별자다
 * @param facts     사람이 읽을 사실 문장들. <b>코드가 만든다</b> — 타임라인 요약을 그대로 옮긴다
 * @param amounts   등장이 허용된 금액(원). 타임라인과 대사 결과에서만 나온다
 * @param dates     등장이 허용된 날짜
 * @param causeHint 규칙 분류기가 제안한 원인. 없으면 null — <b>모르면 모른다고 둔다</b>
 * @param complete  타임라인이 완전한가. false면 초안에 "일부 확인 불가"를 명시해야 한다
 */
public record FactPack(String orderNo,
                       List<String> facts,
                       Set<Long> amounts,
                       Set<LocalDate> dates,
                       String causeHint,
                       boolean complete,
                       boolean internalRecordAbsent) {

    /**
     * 대사 결과 유형을 모르는 자리(초안 경로 등)를 위한 형태. {@code internalRecordAbsent} 는
     * <b>false</b> 로 둔다 — "확인되지 않음"을 "없음"으로 읽으면 가드가 헐거워진다.
     */
    public FactPack(String orderNo, List<String> facts, Set<Long> amounts, Set<LocalDate> dates,
                    String causeHint, boolean complete) {
        this(orderNo, facts, amounts, dates, causeHint, complete, false);
    }

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /**
     * 타임라인과 <b>분류기 제안</b>을 사실 묶음으로 옮긴다. <b>스스로 계산하지 않는다.</b>
     *
     * <p>여기서 차액을 구하거나 합계를 내지 않는 이유: 그 숫자는 이미 대사 엔진과
     * 분류기가 근거와 함께 냈다. 같은 값을 두 곳에서 따로 구하면 언젠가 갈라지고,
     * 갈라진 순간 어느 쪽이 맞는지 알 방법이 없다.
     *
     * <p><b>산문을 파싱하지 않는다.</b> 예전에는 사실 문장과 근거 문장에 정규식을 돌려
     * 금액·날짜를 다시 뽑았다. 그 구조가 두 번 물었다. 한 번은 분류기가 계산한 금액이
     * 허용 목록에서 빠져 <b>4건 중 2건이 오반려</b>됐고, 한 번은 날짜 정규식 뒤에 {@code \b}를
     * 붙여 {@code 2026-09-06T11:06} 같은 ISO 시각을 통째로 놓쳤다.
     *
     * <p>둘 다 <b>같은 원인</b>이다. 허용 목록을 "코드가 만든 문장"에서 되뽑으면,
     * 문구를 고칠 때 허용 범위가 조용히 바뀐다. 그래서 값을 만든 쪽이 값을 함께 낸다 —
     * {@link TimelineEntry#figures()}, {@link TimelineEntry#mentionedDates()},
     * {@link CauseSuggestion#figures()}. 문장은 사람이 읽으라고 있는 것이지 계약이 아니다.
     */
    public static FactPack from(OrderTimeline timeline, CauseSuggestion suggestion) {
        List<String> facts = new ArrayList<>();
        Set<Long> amounts = new LinkedHashSet<>();
        Set<LocalDate> dates = new LinkedHashSet<>();

        for (TimelineEntry e : timeline.entries()) {
            dates.add(e.at().atZone(KST).toLocalDate());
            dates.addAll(e.mentionedDates());
            if (e.amount() != null) {
                amounts.add(Math.abs(e.amount()));
            }
            e.figures().forEach(v -> amounts.add(Math.abs(v)));
            facts.add("%s · %s · %s".formatted(
                    e.at().atZone(KST).toLocalDate(), e.source(), e.summary()));
        }
        if (suggestion != null) {
            amounts.addAll(suggestion.figures());
        }

        // 대사 결과 유형을 <구조화된 신호>로 함께 낸다. 문장("내부 없음")을 정규식으로 되뽑으면
        // 문구를 고칠 때 가드가 조용히 깨진다 — 허용 목록에서 이미 한 번 겪은 실패다.
        boolean internalRecordAbsent = timeline.entries().stream()
                .anyMatch(e -> e.source() == TimelineEntry.Source.RECONCILIATION
                        && "RECON_EXTERNAL_ONLY".equals(e.event()));

        return new FactPack(timeline.orderNo(), List.copyOf(facts),
                Set.copyOf(amounts), Set.copyOf(dates), render(suggestion), timeline.complete(),
                internalRecordAbsent);
    }

    /** 분류기 제안을 사람이 읽을 한 줄로. 값은 {@code figures} 가 따로 들고 있다. */
    private static String render(CauseSuggestion s) {
        return s == null ? null
                : "%s (%s) — %s".formatted(s.cause(), s.confidence(), s.evidence());
    }

    /** 사실이 하나도 없으면 초안을 만들 게 없다. 억지로 만들면 그게 곧 지어내기다. */
    public boolean empty() {
        return facts.isEmpty();
    }
}
