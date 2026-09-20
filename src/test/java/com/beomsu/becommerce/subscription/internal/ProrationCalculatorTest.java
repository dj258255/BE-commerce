package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.subscription.internal.ProrationCalculator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class ProrationCalculatorTest {

    private static final LocalDate START = LocalDate.of(2026, 1, 1);
    private static final LocalDate END = LocalDate.of(2026, 1, 31); // 총 30일 주기

    @Test
    @DisplayName("업그레이드: 남은 기간 비율만큼 차액을 추가 청구(양수)")
    void upgradeChargesDifference() {
        // 15일 남음(1/16 변경), 차액 20000 → 20000 * 15/30 = 10000
        long result = ProrationCalculator.prorate(
                10_000, 30_000, START, END, LocalDate.of(2026, 1, 16));

        assertThat(result).isEqualTo(10_000);
    }

    @Test
    @DisplayName("다운그레이드: 남은 기간 비율만큼 크레딧(음수)")
    void downgradeCreditsDifference() {
        // 15일 남음, 차액 -20000 → -20000 * 15/30 = -10000
        long result = ProrationCalculator.prorate(
                30_000, 10_000, START, END, LocalDate.of(2026, 1, 16));

        assertThat(result).isEqualTo(-10_000);
    }

    @Test
    @DisplayName("주기 시작일에 변경하면 전체 차액을 청구한다")
    void changeAtPeriodStartChargesFullDifference() {
        // 30일 전부 남음 → 차액 그대로
        long result = ProrationCalculator.prorate(
                10_000, 25_000, START, END, START);

        assertThat(result).isEqualTo(15_000);
    }

    @Test
    @DisplayName("주기 종료일(=다음 청구일)에 변경하면 남은 기간이 없어 0")
    void changeAtPeriodEndIsZero() {
        long result = ProrationCalculator.prorate(
                10_000, 30_000, START, END, END);

        assertThat(result).isZero();
    }

    @Test
    @DisplayName("나누어떨어지지 않으면 원 단위 내림")
    void flooredToWon() {
        // 10일 남음(1/21 변경), 차액 10000 → 10000 * 10/30 = 3333.33... → 3333
        long result = ProrationCalculator.prorate(
                10_000, 20_000, START, END, LocalDate.of(2026, 1, 21));

        assertThat(result).isEqualTo(3_333);
    }

    @Test
    @DisplayName("주기 길이가 0이면 안분 불가로 0을 반환한다")
    void zeroPeriodReturnsZero() {
        long result = ProrationCalculator.prorate(
                10_000, 20_000, START, START, START);

        assertThat(result).isZero();
    }
}
