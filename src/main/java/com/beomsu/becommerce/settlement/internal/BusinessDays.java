package com.beomsu.becommerce.settlement.internal;

import java.time.DayOfWeek;
import java.time.LocalDate;

/**
 * 영업일 계산기 — 주말(토·일)을 건너뛰며 N영업일을 더한다.
 *
 * <p><b>가정</b>: 법정공휴일은 미반영이다. 주말만 skip하므로 실제 지급예정일과는 공휴일만큼의 오차가
 * 있을 수 있다(공휴일 캘린더 연동은 후속 과제). 정산 지급예정일 산출에 쓰인다.
 */
final class BusinessDays {

    private BusinessDays() {
    }

    /**
     * {@code start}에 {@code businessDays}만큼 영업일을 더한 날짜를 반환한다(주말 skip).
     *
     * <p>예: 금요일 + 2영업일 = 화요일(토·일을 건너뜀). {@code businessDays <= 0}이면 {@code start} 반환.
     */
    static LocalDate plusBusinessDays(LocalDate start, int businessDays) {
        LocalDate date = start;
        int added = 0;
        while (added < businessDays) {
            date = date.plusDays(1);
            DayOfWeek dow = date.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return date;
    }
}
