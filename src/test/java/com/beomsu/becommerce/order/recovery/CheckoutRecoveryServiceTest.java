package com.beomsu.becommerce.order.recovery;

import static org.mockito.ArgumentMatchers.any;

import org.springframework.data.domain.Pageable;

import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.order.internal.CheckoutTx;
import com.beomsu.becommerce.payment.ApprovalOutcome;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.wallet.WalletService;
import com.beomsu.becommerce.payment.StuckPaymentInfo;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CheckoutRecoveryServiceTest {

    private OrderRepository orderRepository;
    private PaymentService paymentService;
    private WalletService walletService;
    private CheckoutTx checkoutTx;
    private CheckoutRecoveryService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentService = mock(PaymentService.class);
        walletService = mock(WalletService.class);
        checkoutTx = mock(CheckoutTx.class);
        service = new CheckoutRecoveryService(orderRepository, paymentService, walletService, checkoutTx);
        ReflectionTestUtils.setField(service, "stuckAfterMinutes", 10L);
    }

    private Order stuckOrder(long total) {
        Order order = Order.create(1L, List.of(OrderItem.of(100L, "상품A", total, 1)));
        return order;
    }

    @Test
    @DisplayName("멈춘 주문(카드 결제 DONE으로 확정): 카드금액·포인트분을 도출해 settle을 재실행한다")
    void recoversStuckOrderWithResolvedCardPayment() {
        Order order = stuckOrder(20_000);
        when(orderRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PAYMENT_IN_PROGRESS), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(order));
        // 카드 14,000 결제가 PG 조회로 DONE 확정됨 → 포인트분은 20,000-14,000=6,000
        when(paymentService.resolveStuckPayment(order.getOrderNo())).thenReturn(Optional.of(
                new StuckPaymentInfo(1L, 14_000, new ApprovalOutcome(ApprovalOutcome.Result.SUCCESS, "CARD", null))));

        int recovered = service.recoverStuckCheckouts();

        assertThat(recovered).isEqualTo(1);
        verify(checkoutTx).settle(eq(order.getOrderNo()), eq(1L), eq(Money.krw(14_000)), eq(6_000L), eq(0L),
                any(ApprovalOutcome.class));
    }

    @Test
    @DisplayName("멈춘 전액 포인트 주문(카드 결제 없음): outcome=null, cardAmount=0으로 settle 재실행")
    void recoversStuckFullPointOrder() {
        Order order = stuckOrder(20_000);
        when(orderRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PAYMENT_IN_PROGRESS), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(order));
        when(paymentService.resolveStuckPayment(order.getOrderNo())).thenReturn(Optional.empty());

        service.recoverStuckCheckouts();

        // 전액 포인트: cardAmount 0, pointAmount 20,000, outcome null
        verify(checkoutTx).settle(eq(order.getOrderNo()), isNull(), eq(Money.krw(0)), eq(20_000L), eq(0L), isNull());
    }

    @Test
    @DisplayName("실패한 건은 시도 시각을 남겨 뒤로 보낸다 — 안 그러면 상한 밖의 건이 영영 차례를 못 받는다")
    void failedItemIsPushedBackSoOthersGetATurn() {
        Order bad = stuckOrder(10_000);
        when(orderRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(OrderStatus.PAYMENT_IN_PROGRESS), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(bad));
        when(paymentService.resolveStuckPayment(bad.getOrderNo()))
                .thenThrow(new RuntimeException("PG 조회 실패"));

        service.recoverStuckCheckouts();

        // 실패해도 <시도했다>를 남겨야 한다. 안 남기면 updatedAt 이 그대로라 다음 회차에도
        // 같은 앞자리를 잡고, 한 번에 읽는 수에 상한이 있으므로 뒤의 건은 차례가 안 온다.
        verify(checkoutTx).markRecoveryAttempted(bad.getId());
    }

    @Test
    @DisplayName("기록마저 실패해도 배치는 멈추지 않는다")
    void markingFailureDoesNotStopTheBatch() {
        Order bad = stuckOrder(10_000);
        Order good = stuckOrder(20_000);
        when(orderRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(
                eq(OrderStatus.PAYMENT_IN_PROGRESS), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(bad, good));
        when(paymentService.resolveStuckPayment(bad.getOrderNo()))
                .thenThrow(new RuntimeException("PG 조회 실패"));
        doThrow(new RuntimeException("기록 실패")).when(checkoutTx).markRecoveryAttempted(bad.getId());
        when(paymentService.resolveStuckPayment(good.getOrderNo())).thenReturn(Optional.empty());

        int recovered = service.recoverStuckCheckouts();

        assertThat(recovered).as("다음 건의 차례를 지키자고 시작한 일이라 여기서 멈추면 안 된다")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("한 건 실패가 배치를 멈추지 않는다 — 격리하고 다음 건 계속 처리")
    void perItemFailureIsolated() {
        Order bad = stuckOrder(10_000);
        Order good = stuckOrder(20_000);
        when(orderRepository.findByStatusAndUpdatedAtBeforeOrderByUpdatedAtAsc(eq(OrderStatus.PAYMENT_IN_PROGRESS), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(bad, good));
        when(paymentService.resolveStuckPayment(bad.getOrderNo())).thenThrow(new RuntimeException("PG 조회 실패"));
        when(paymentService.resolveStuckPayment(good.getOrderNo())).thenReturn(Optional.empty());

        int recovered = service.recoverStuckCheckouts();

        // bad는 실패, good은 성공 → 1건 복구, good에 대해 settle 호출됨
        assertThat(recovered).isEqualTo(1);
        verify(checkoutTx).settle(eq(good.getOrderNo()), isNull(), any(Money.class), anyLong(), anyLong(), isNull());
    }
}
