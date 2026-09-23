package com.beomsu.becommerce.timeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 타임라인 조립기 (ADR-011).
 *
 * <p>여기서 지키려는 것은 <b>부분 실패 처리</b>다. 한 도메인 조회가 죽었을 때
 * 전부 버리면 "정산 조회가 죽어서 아무것도 못 본다"가 되고, 조용히 빼면
 * "정산 기록이 없다"로 잘못 읽힌다. <b>살리되 빠진 것을 알린다</b>가 이 클래스의 계약이고,
 * 그게 깨지면 대사 담당자가 잘못된 근거로 원인을 확정하게 된다.
 */
class OrderTimelineServiceTest {

    private static final Instant T0 = Instant.parse("2026-08-29T10:00:00Z");

    /** 지정한 항목을 그대로 돌려주는 기여자. */
    private static TimelineContributor giving(TimelineEntry.Source source, TimelineEntry... entries) {
        return new TimelineContributor() {
            @Override public List<TimelineEntry> contribute(String orderNo) { return List.of(entries); }
            @Override public TimelineEntry.Source source() { return source; }
        };
    }

    /** 항상 터지는 기여자 — 조회 대상 DB가 죽은 상황. */
    private static TimelineContributor failing(TimelineEntry.Source source) {
        return new TimelineContributor() {
            @Override public List<TimelineEntry> contribute(String orderNo) {
                throw new IllegalStateException("조회 실패");
            }
            @Override public TimelineEntry.Source source() { return source; }
        };
    }

    @Test
    @DisplayName("여러 도메인의 사실을 시간순으로 합친다 — 도메인 순서가 아니라 일어난 순서")
    void mergesInChronologicalOrder() {
        var service = new OrderTimelineService(List.of(
                giving(TimelineEntry.Source.LEDGER,
                        TimelineEntry.of(T0.plusSeconds(30), TimelineEntry.Source.LEDGER, "L", "분개")),
                giving(TimelineEntry.Source.ORDER,
                        TimelineEntry.of(T0, TimelineEntry.Source.ORDER, "O", "주문 생성")),
                giving(TimelineEntry.Source.PAYMENT,
                        TimelineEntry.of(T0.plusSeconds(10), TimelineEntry.Source.PAYMENT, "P", "승인"))));

        var timeline = service.assemble("ord-1");

        assertThat(timeline.entries())
                .extracting(TimelineEntry::event)
                .containsExactly("O", "P", "L");   // 등록 순서(L,O,P)가 아니라 시간순
        assertThat(timeline.complete()).isTrue();
    }

    @Test
    @DisplayName("한 도메인이 실패해도 나머지는 살린다 — 그리고 빠진 출처를 응답에 싣는다")
    void survivesPartialFailureAndReportsIt() {
        var service = new OrderTimelineService(List.of(
                giving(TimelineEntry.Source.ORDER,
                        TimelineEntry.of(T0, TimelineEntry.Source.ORDER, "O", "주문 생성")),
                failing(TimelineEntry.Source.SETTLEMENT),
                giving(TimelineEntry.Source.PAYMENT,
                        TimelineEntry.of(T0.plusSeconds(5), TimelineEntry.Source.PAYMENT, "P", "승인"))));

        var timeline = service.assemble("ord-1");

        // 살아남은 둘은 그대로 보인다 — 하나 죽었다고 조사를 못 하게 만들지 않는다
        assertThat(timeline.entries()).hasSize(2);
        // 그리고 무엇이 빠졌는지 반드시 알린다 — 조용히 빠지면 "정산 기록 없음"으로 오독된다
        assertThat(timeline.unavailable()).containsExactly("SETTLEMENT");
        assertThat(timeline.complete())
                .as("빠진 출처가 있으면 이 타임라인만 보고 판단하면 안 된다")
                .isFalse();
    }

    @Test
    @DisplayName("전부 실패해도 예외를 던지지 않는다 — 무엇이 죽었는지가 그 자체로 정보다")
    void allFailingStillReturns() {
        var service = new OrderTimelineService(List.of(
                failing(TimelineEntry.Source.ORDER), failing(TimelineEntry.Source.PAYMENT)));

        var timeline = service.assemble("ord-1");

        assertThat(timeline.entries()).isEmpty();
        assertThat(timeline.unavailable()).containsExactlyInAnyOrder("ORDER", "PAYMENT");
        assertThat(timeline.complete()).isFalse();
    }

    @Test
    @DisplayName("사건이 없는 도메인은 빈 목록을 준다 — 없는 것과 실패한 것은 다르다")
    void emptyIsNotFailure() {
        var service = new OrderTimelineService(List.of(
                giving(TimelineEntry.Source.ORDER,
                        TimelineEntry.of(T0, TimelineEntry.Source.ORDER, "O", "주문 생성")),
                giving(TimelineEntry.Source.POINT)));   // 포인트를 안 쓴 주문

        var timeline = service.assemble("ord-1");

        assertThat(timeline.entries()).hasSize(1);
        assertThat(timeline.unavailable())
                .as("포인트를 안 쓴 것은 정상이지 조회 실패가 아니다")
                .isEmpty();
        assertThat(timeline.complete()).isTrue();
    }

    @Test
    @DisplayName("같은 시각의 사건은 출처 이름으로 안정 정렬한다 — 새로고침마다 순서가 바뀌면 안 된다")
    void stableOrderForSameInstant() {
        var service = new OrderTimelineService(List.of(
                giving(TimelineEntry.Source.WALLET,
                        TimelineEntry.of(T0, TimelineEntry.Source.WALLET, "W", "월렛")),
                giving(TimelineEntry.Source.POINT,
                        TimelineEntry.of(T0, TimelineEntry.Source.POINT, "P", "포인트"))));

        assertThat(service.assemble("ord-1").entries())
                .extracting(e -> e.source().name())
                .containsExactly("POINT", "WALLET");   // P < W
    }
}
