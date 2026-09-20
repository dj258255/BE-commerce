package com.beomsu.becommerce.subscription.billing;

import java.time.LocalDate;

/**
 * 다음 청구일 계산 — 순수 함수.
 *
 * <p><b>왜 {@code plusMonths}만으로는 안 되는가</b>: {@link LocalDate#plusMonths}는 그 달에 없는
 * 날짜를 말일로 당긴다. 1월 31일에 한 달을 더하면 2월 28일이다. 여기까지는 맞다. 문제는 그 결과를
 * <b>다시 기준으로 삼을 때</b>다. 2월 28일에 한 달을 더하면 3월 28일이 되어, 3월에는 31일이 있는데도
 * 28일에 청구한다. 한 번 당겨진 날이 영영 돌아오지 않고 <b>손실이 누적</b>된다.
 *
 * <pre>
 *   기준일에서 누적    1/31 → 2/28 → 3/28 → 4/28 → 5/28   (31이 사라진다)
 *   앵커에서 매번 계산  1/31 → 2/28 → 3/31 → 4/30 → 5/31   (31이 살아 있다)
 * </pre>
 *
 * <p>그래서 구독이 <b>원래 몇 일에 청구되기로 했는지</b>(anchorDay)를 따로 들고, 매달 그 달 길이에
 * 맞춰 클램프한다. Stripe 의 billing cycle anchor 와 같은 방식이다 —
 * 앵커가 31이면 2월은 28(윤년 29), 3월은 다시 31로 돌아온다.
 */
final // 하위 패키지가 같은 모듈 안에서 참조하므로 public 이다. 모듈 밖 접근은 package-private 이 아니라
// ModularityTests 의 allowedDependencies 가 막는다.
public class BillingCycle {

    private BillingCycle() {
    }

    /**
     * 앵커를 기준으로 다음 청구일을 낸다.
     *
     * @param from      직전 청구일(이미 클램프됐을 수 있다)
     * @param anchorDay 원래 청구하기로 한 일자(1~31)
     * @param months    청구 주기(개월)
     * @return 다음 청구일. 그 달에 앵커 일자가 없으면 말일
     */
    public static LocalDate next(LocalDate from, int anchorDay, int months) {
        LocalDate base = from.plusMonths(months);
        return base.withDayOfMonth(Math.min(anchorDay, base.lengthOfMonth()));
    }

    /**
     * 구독 시작일에서 앵커를 뽑는다.
     *
     * <p>시작일 자체는 클램프된 적이 없으므로 그 일자가 곧 앵커다. 2월 28일에 시작한 구독의 앵커는
     * 28이지 말일이 아니다 — "매월 말일"을 원하면 말일 시작일로 구독해야 하고, 그 경우 앵커는
     * 그 달의 말일 숫자(28·30·31)가 된다.
     */
    public static int anchorOf(LocalDate startDate) {
        return startDate.getDayOfMonth();
    }
}
