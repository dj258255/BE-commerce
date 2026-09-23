package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.CouponAllocator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CouponAllocatorTest {

    private static long sum(long[] a) {
        long s = 0;
        for (long v : a) {
            s += v;
        }
        return s;
    }

    @Test
    @DisplayName("비율 배분: 라인 금액 비례로 나누고 합계는 쿠폰액과 정확히 일치")
    void allocatesProportionally() {
        // 쿠폰 1,000을 라인 6,000/4,000에 배분 → 600 / 400
        long[] result = CouponAllocator.allocate(1_000, new long[]{6_000, 4_000});

        assertThat(result).containsExactly(600, 400);
        assertThat(sum(result)).isEqualTo(1_000); // 불변식
    }

    @Test
    @DisplayName("끝전(단수차)은 마지막 라인에 몰아주어 1원 오차도 없다")
    void remainderGoesToLastLine() {
        // 쿠폰 1,000을 3등분(3,000/3,000/3,000): 각 333, 합 999 → 마지막 라인 +1 = 334
        long[] result = CouponAllocator.allocate(1_000, new long[]{3_000, 3_000, 3_000});

        assertThat(result).containsExactly(333, 333, 334);
        assertThat(sum(result)).isEqualTo(1_000); // 끝전 포함 합계 불변식
    }

    @Test
    @DisplayName("불균등한 라인에서도 내림 + 끝전 몰아주기로 합계 불변식 유지")
    void unevenLinesStillSumExactly() {
        // 쿠폰 999를 라인 7,777/1,111/1,112 에 배분해도 합계는 정확히 999
        long[] result = CouponAllocator.allocate(999, new long[]{7_777, 1_111, 1_112});

        assertThat(sum(result)).isEqualTo(999);
        // 마지막을 제외한 라인은 내림값, 끝전은 마지막에 흡수
        assertThat(result[result.length - 1]).isGreaterThanOrEqualTo(0);
    }
}
