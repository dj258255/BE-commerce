package com.beomsu.becommerce.subscription.billing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 청구일 계산 — 말일과 윤년.
 *
 * <p>이 테스트가 잡는 것은 <b>손실 누적</b>이다. 직전 청구일에서 한 달씩 더하면 2월에 한 번 당겨진
 * 날이 영영 돌아오지 않는다. 앵커에서 매번 계산해야 3월에 31일로 복귀한다.
 */
class BillingCycleTest {

    private static List<LocalDate> series(LocalDate start, int months) {
        int anchor = BillingCycle.anchorOf(start);
        List<LocalDate> out = new ArrayList<>();
        LocalDate cur = start;
        for (int i = 0; i < months; i++) {
            cur = BillingCycle.next(cur, anchor, 1);
            out.add(cur);
        }
        return out;
    }

    @Test
    @DisplayName("31일 앵커: 2월에 당겨져도 3월에 31일로 돌아온다")
    void anchorSurvivesShortMonth() {
        assertThat(series(LocalDate.of(2026, 1, 31), 5)).containsExactly(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 31),
                LocalDate.of(2026, 6, 30));
    }

    @Test
    @DisplayName("직전 청구일에서 더하면 31일이 영영 사라진다 — 고치기 전 동작")
    void naivePlusMonthsLosesTheAnchor() {
        LocalDate cur = LocalDate.of(2026, 1, 31);
        List<LocalDate> naive = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            cur = cur.plusMonths(1);   // 앵커 없이 직전 값에서 누적
            naive.add(cur);
        }
        // 2월에 28로 당겨진 뒤 3·5월에 31일이 있는데도 28에 갇힌다.
        assertThat(naive).containsExactly(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 28),
                LocalDate.of(2026, 4, 28),
                LocalDate.of(2026, 5, 28),
                LocalDate.of(2026, 6, 28));
    }

    @Test
    @DisplayName("윤년 2월은 29일까지 간다")
    void leapYearFebruary() {
        // 2028년은 윤년(4로 나뉘고 100으로 안 나뉨).
        assertThat(BillingCycle.next(LocalDate.of(2028, 1, 31), 31, 1))
                .isEqualTo(LocalDate.of(2028, 2, 29));
    }

    @Test
    @DisplayName("30일 앵커: 2월만 당겨지고 나머지 달은 30일을 지킨다")
    void anchorThirty() {
        assertThat(series(LocalDate.of(2026, 1, 30), 4)).containsExactly(
                LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 30),
                LocalDate.of(2026, 4, 30),
                LocalDate.of(2026, 5, 30));
    }

    @Test
    @DisplayName("28일 이하 앵커는 어느 달에도 당겨지지 않는다")
    void safeAnchorNeverClamps() {
        assertThat(series(LocalDate.of(2026, 1, 15), 3)).containsExactly(
                LocalDate.of(2026, 2, 15),
                LocalDate.of(2026, 3, 15),
                LocalDate.of(2026, 4, 15));
    }

    @Test
    @DisplayName("2월 28일에 시작한 구독의 앵커는 28이지 말일이 아니다")
    void februaryStartIsNotMonthEnd() {
        // "매월 말일"을 원하면 말일 시작일로 구독해야 한다. 시작일의 일자가 곧 앵커다.
        assertThat(series(LocalDate.of(2026, 2, 28), 2)).containsExactly(
                LocalDate.of(2026, 3, 28),
                LocalDate.of(2026, 4, 28));
    }
}
