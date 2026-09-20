package com.beomsu.becommerce.order.internal;

/**
 * 쿠폰 안분기 — 주문 단위 쿠폰 할인액을 라인별로 배분한다(정산 금액 배분).
 *
 * <p>라인 배분액 = 쿠폰액 × (라인금액 / 주문합계), 원 단위 내림. 내림으로 생기는 끝전(단수차)은
 * 마지막 라인에 몰아준다. <b>불변식: sum(라인 배분액) == 쿠폰액</b> — 배민 정산팀 요구인
 * "1원 오차 없이"를 순수 함수로 보장한다.
 */
public final class CouponAllocator {

    private CouponAllocator() {
    }

    /**
     * 쿠폰액을 라인 금액 비율로 안분한다.
     *
     * @param couponAmount 배분할 쿠폰(할인) 총액
     * @param lineAmounts  라인별 금액(모두 0 이상, 합계 &gt; 0)
     * @return 라인별 배분액(합계는 정확히 couponAmount)
     */
    public static long[] allocate(long couponAmount, long[] lineAmounts) {
        if (couponAmount < 0) {
            throw new IllegalArgumentException("쿠폰액은 음수일 수 없습니다: " + couponAmount);
        }
        if (lineAmounts == null || lineAmounts.length == 0) {
            throw new IllegalArgumentException("라인이 비어 있습니다.");
        }
        long total = 0;
        for (long line : lineAmounts) {
            if (line < 0) {
                throw new IllegalArgumentException("라인 금액은 음수일 수 없습니다: " + line);
            }
            total = Math.addExact(total, line);
        }
        if (total == 0) {
            throw new IllegalArgumentException("라인 금액 합계가 0이면 비율 배분할 수 없습니다.");
        }

        long[] result = new long[lineAmounts.length];
        long allocated = 0;
        for (int i = 0; i < lineAmounts.length; i++) {
            // 원 단위 내림. 곱 오버플로는 Math.multiplyExact로 방어한다.
            long share = Math.multiplyExact(couponAmount, lineAmounts[i]) / total;
            result[i] = share;
            allocated += share;
        }
        // 끝전(단수차)은 마지막 라인에 몰아주기 → 합계 불변식(sum == couponAmount) 보장
        result[result.length - 1] += couponAmount - allocated;
        return result;
    }
}
