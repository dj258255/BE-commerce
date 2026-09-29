package com.beomsu.becommerce.order.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 남은 시간 헤더({@code X-Request-Timeout-Ms})를 서버 시계 기준 마감으로 바꾸는 규칙(#409 데드라인 전파).
 * 클라이언트 시계 값을 쓰지 않으므로 두 시계가 달라도 판정이 흔들리지 않는다.
 */
class CheckoutControllerDeadlineTest {

    private static final long NOW = 1_790_000_000_000L;

    @Test
    @DisplayName("헤더가 없으면 데드라인을 두지 않는다")
    void noHeaderMeansNoDeadline() {
        assertThat(CheckoutController.serverDeadlineMs(null, NOW)).isNull();
    }

    @Test
    @DisplayName("남은 시간은 받은 순간의 서버 시계에 더한다")
    void remainingIsAddedToServerClock() {
        assertThat(CheckoutController.serverDeadlineMs(30_000L, NOW)).isEqualTo(NOW + 30_000L);
    }

    @Test
    @DisplayName("0 이하는 이미 지난 것으로 보고 받은 순간보다 앞을 돌려준다 — now > deadline 비교가 같은 밀리초에 통과하지 않는다")
    void zeroOrNegativeIsAlreadyExpired() {
        assertThat(CheckoutController.serverDeadlineMs(0L, NOW)).isLessThan(NOW);
        assertThat(CheckoutController.serverDeadlineMs(-500L, NOW)).isLessThan(NOW);
    }
}
