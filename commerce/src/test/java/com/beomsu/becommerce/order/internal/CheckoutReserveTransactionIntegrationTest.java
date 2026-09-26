package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.point.PointService;
import com.beomsu.becommerce.shared.Money;
import com.beomsu.becommerce.testsupport.SharedContainers;
import com.beomsu.becommerce.wallet.WalletService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 결제 확정의 예약 단계(Phase 1)는 <b>한 트랜잭션</b>이어야 한다(#375).
 *
 * <p>단위 테스트는 {@link CheckoutTx} 를 목으로 바꿔 트랜잭션 경계를 보지 못했다. 할부 인자를 더한 7인자
 * {@code reserve} 에 {@code @Transactional} 이 빠져 예약 단계의 저장소 호출이 각각 따로 커밋됐고, 뒤 단계가
 * 실패해도 주문 전이 · 결제 행 · 포인트 사용이 남았다. 실 MySQL 로 그 경계를 잰다.
 */
@Tag("integration")
@SpringBootTest
@DisplayName("결제 확정의 예약 단계 — 뒤에서 실패하면 앞의 것도 함께 롤백되는가")
class CheckoutReserveTransactionIntegrationTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry props) {
        SharedContainers.register(props, "CheckoutReserveTx");
    }

    @Autowired
    CheckoutService checkoutService;

    @Autowired
    CheckoutTx checkoutTx;

    @Autowired
    PointService pointService;

    @Autowired
    WalletService walletService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    @DisplayName("월렛 차감이 잔액 부족으로 실패하면 주문 전이 · 결제 행 · 포인트 사용이 함께 롤백된다")
    void walletFailureRollsBackWholeReservation() {
        long userId = 9_375L;
        pointService.earn(userId, 1_000, "seed-point-" + UUID.randomUUID());
        walletService.charge(userId, 1_000);
        CreateOrderResult order = checkoutService.createOrder(userId, List.of(new OrderLine(1L, 1)));   // 10,000원
        long pointsBefore = pointService.balance(userId);

        // 카드 8,000 + 포인트 500 + 월렛 1,500(잔액 1,000 보다 많다) = 10,000. 월렛 차감은 예약 단계의 맨 마지막이다
        assertThatThrownBy(() -> checkoutTx.reserve(order.orderNo(), "pk-375-" + UUID.randomUUID(),
                Money.krw(8_000), 500, 1_500, userId, 0))
                .isInstanceOf(RuntimeException.class);

        String status = jdbc.queryForObject("SELECT status FROM orders WHERE order_no = ?", String.class,
                order.orderNo());
        Integer payments = jdbc.queryForObject("SELECT COUNT(*) FROM payments WHERE order_no = ?", Integer.class,
                order.orderNo());
        System.out.printf("%n  월렛 부족 뒤: 주문 %s, 결제 행 %d, 포인트 %d → %d%n",
                status, payments, pointsBefore, pointService.balance(userId));

        assertThat(status).isEqualTo("PENDING_PAYMENT");
        assertThat(payments).isZero();
        assertThat(pointService.balance(userId)).isEqualTo(pointsBefore);
    }
}
