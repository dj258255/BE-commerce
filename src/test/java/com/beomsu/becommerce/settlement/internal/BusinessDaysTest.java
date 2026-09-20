package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.settlement.internal.BusinessDays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class BusinessDaysTest {

    @Test
    @DisplayName("금요일 + 2영업일 = 다음 주 화요일 (토·일 skip)")
    void fridayPlusTwoBusinessDaysIsTuesday() {
        LocalDate friday = LocalDate.of(2026, 7, 3); // 2026-07-03은 금요일
        assertThat(friday.getDayOfWeek().getValue()).isEqualTo(5); // 사전조건: 금요일

        LocalDate result = BusinessDays.plusBusinessDays(friday, 2);

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 7)); // 화요일
    }

    @Test
    @DisplayName("월요일 + 2영업일 = 수요일 (주말 없음)")
    void mondayPlusTwoBusinessDaysIsWednesday() {
        LocalDate monday = LocalDate.of(2026, 7, 6); // 월요일
        assertThat(monday.getDayOfWeek().getValue()).isEqualTo(1);

        LocalDate result = BusinessDays.plusBusinessDays(monday, 2);

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 8)); // 수요일
    }

    @Test
    @DisplayName("토요일 + 1영업일 = 월요일 (시작이 주말이어도 다음 영업일로)")
    void saturdayPlusOneBusinessDayIsMonday() {
        LocalDate saturday = LocalDate.of(2026, 7, 4); // 토요일
        assertThat(saturday.getDayOfWeek().getValue()).isEqualTo(6);

        LocalDate result = BusinessDays.plusBusinessDays(saturday, 1);

        assertThat(result).isEqualTo(LocalDate.of(2026, 7, 6)); // 월요일
    }

    @Test
    @DisplayName("0영업일이면 시작일 그대로")
    void zeroBusinessDaysReturnsStart() {
        LocalDate date = LocalDate.of(2026, 7, 6);
        assertThat(BusinessDays.plusBusinessDays(date, 0)).isEqualTo(date);
    }
}
