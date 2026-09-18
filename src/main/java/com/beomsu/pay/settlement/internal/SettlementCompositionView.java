package com.beomsu.pay.settlement.internal;

import java.time.LocalDate;
import java.util.List;

/**
 * 정산 한 건이 <b>언제 번 돈으로</b> 이뤄졌는지.
 *
 * <p>배치는 그 날짜 <b>이하</b>의 미정산 항목을 쓸어 담는다. 늦게 확정된 건이 영영 빠지지 않게
 * 하려는 것이고 그 목적은 달성했다. 대신 {@code settlement_date} 가 <b>돈을 번 날이 아니라
 * 쓸어 담은 날</b>이 된다. 정산 한 건을 보고 "이 금액 중 얼마가 며칠 전 거래인지"를 답할 자리가
 * 없었다(ADR-023).
 *
 * <p>이 뷰는 그 답을 <b>읽기만으로</b> 만든다. 항목이 이미 확정일과 소속 정산을 들고 있어서
 * 스키마도 쓰기 경로도 지급 로직도 건드리지 않는다. 과거를 고치지 않는다는 결정
 * ({@link SettlementAdjustment})은 그대로 두고, 그 대가만 보이게 한다.
 *
 * @param settlementDate  쓸어 담은 날 (정산 집계일)
 * @param totalAmount     정산 총액
 * @param sameDateAmount  집계일과 확정일이 같은 금액
 * @param carriedAmount   다른 날짜에서 넘어온 금액
 * @param crossesMonth    월 경계를 넘어온 항목이 있는지. 월 단위 리포트가 어긋나는 자리다
 * @param byConfirmedDate 확정일별 내역 (오래된 날짜 먼저)
 */
public record SettlementCompositionView(
        LocalDate settlementDate,
        long totalAmount,
        long sameDateAmount,
        long carriedAmount,
        boolean crossesMonth,
        List<Line> byConfirmedDate) {

    /** 확정일 하나의 몫. {@code daysLate} 가 0 이면 그날 번 돈이다. */
    public record Line(LocalDate confirmedDate, int itemCount, long amount, long daysLate) {}
}
