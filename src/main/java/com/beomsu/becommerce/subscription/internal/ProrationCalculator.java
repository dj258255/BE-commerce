package com.beomsu.becommerce.subscription.internal;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * 플랜 변경 일할계산(proration) — 순수 함수.
 *
 * <p>Stripe 방식: 변경 시점 기준으로 <b>구 플랜 미사용분 크레딧</b>과 <b>신 플랜 잔여기간 청구</b>를
 * 합산한다. 남은 기간 비율(남은일수/총일수)로 신·구 차액을 안분한다.
 * <ul>
 *   <li>업그레이드(신 &gt; 구): 양수 = 즉시 추가 청구</li>
 *   <li>다운그레이드(신 &lt; 구): 음수/0 = 크레딧(현금 환불 없음)</li>
 * </ul>
 * 원 단위 내림({@link Math#floorDiv})으로 절사한다.
 */
public final class ProrationCalculator {

    private ProrationCalculator() {
    }

    /**
     * @param oldAmount   기존 플랜 금액(원)
     * @param newAmount   변경 후 플랜 금액(원)
     * @param periodStart 현재 청구 주기 시작일(포함)
     * @param periodEnd   현재 청구 주기 종료일 = 다음 청구일(제외)
     * @param changeDate  플랜 변경일
     * @return 변경으로 발생하는 정산액(원). 양수=추가 청구, 음수=크레딧, 0=변동 없음
     */
    public static long prorate(long oldAmount, long newAmount,
                               LocalDate periodStart, LocalDate periodEnd, LocalDate changeDate) {
        long totalDays = ChronoUnit.DAYS.between(periodStart, periodEnd);
        if (totalDays <= 0) {
            return 0; // 주기 길이가 없으면 안분 불가
        }
        // 남은 일수: 변경일부터 주기 종료까지. 경계 밖은 [0, totalDays]로 클램프.
        long remainingDays = ChronoUnit.DAYS.between(changeDate, periodEnd);
        remainingDays = Math.max(0, Math.min(remainingDays, totalDays));

        // (신 플랜 잔여기간 청구) - (구 플랜 미사용분 크레딧) = 차액 × 잔여비율.
        long numerator = (newAmount - oldAmount) * remainingDays;
        return Math.floorDiv(numerator, totalDays);
    }
}
