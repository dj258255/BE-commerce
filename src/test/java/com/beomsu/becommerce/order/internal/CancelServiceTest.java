package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.OrderException;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.order.internal.CancelTx;
import com.beomsu.becommerce.order.internal.CancelService;
import com.beomsu.becommerce.order.internal.CancelResult;
import com.beomsu.becommerce.order.catalog.StockDeductionService;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.point.PointService;
import com.beomsu.becommerce.shared.Money;
import com.beomsu.becommerce.wallet.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CancelServiceTest {

    private OrderRepository orderRepository;
    private PaymentService paymentService;
    private PointService pointService;
    private WalletService walletService;
    private StockDeductionService stockDeductionService;
    private CancelService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentService = mock(PaymentService.class);
        pointService = mock(PointService.class);
        walletService = mock(WalletService.class);
        stockDeductionService = mock(StockDeductionService.class);
        // 취소 사가의 트랜잭션 경계 빈을 실제로 조립해 넣는다 — 오케스트레이터와 각 단계를 함께 검증한다.
        CancelTx cancelTx = new CancelTx(orderRepository, paymentService, pointService,
                walletService, stockDeductionService);
        service = new CancelService(cancelTx, paymentService);
    }

    /** userId 1 소유의 10,000 x 2 = 20,000 짜리 PAID 주문. */
    private Order paidOrderOf(long productId, int quantity) {
        Order order = Order.create(1L, List.of(
                OrderItem.of(productId, "상품A", 10_000, quantity)));
        order.startPayment(); // PENDING_PAYMENT → PAYMENT_IN_PROGRESS
        order.markPaid();     // PAYMENT_IN_PROGRESS → PAID
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("전액취소(포인트+카드): 포인트 우선 환불 + 카드 취소 + 재고 복원 + 주문 CANCELED")
    void fullCancelWithPointAndCard() {
        Order order = paidOrderOf(100L, 2); // total 20,000
        when(pointService.refundableAmount(order.getOrderNo())).thenReturn(6_000L);
        when(paymentService.cardBalance(order.getOrderNo())).thenReturn(14_000L);

        CancelResult result = service.cancel(order.getOrderNo(), 20_000, "고객변심", 1L);

        // 포인트 먼저(6,000) 환불하고, 나머지 14,000을 카드에서 취소한다.
        verify(pointService).refund(1L, 6_000, order.getOrderNo());
        verify(paymentService).cancelByOrderNo(order.getOrderNo(), Money.krw(14_000), "고객변심");
        verify(stockDeductionService).restore(100L, 2); // 전액취소 재고 복원
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        // 전액취소 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(orderRepository).saveAndFlush(order);
        assertThat(result.fullyCanceled()).isTrue();
        assertThat(result.refundedPoint()).isEqualTo(6_000);
        assertThat(result.refundedCard()).isEqualTo(14_000);
    }

    @Test
    @DisplayName("전액취소(카드+월렛): 월렛 몫도 환불하고 전액취소로 판정한다(자금손실 회귀)")
    void fullCancelRefundsWalletShare() {
        Order order = paidOrderOf(100L, 2); // total 20,000
        when(pointService.refundableAmount(order.getOrderNo())).thenReturn(0L);
        when(walletService.refundableAmount(order.getOrderNo())).thenReturn(6_000L);
        when(paymentService.cardBalance(order.getOrderNo())).thenReturn(14_000L);

        CancelResult result = service.cancel(order.getOrderNo(), 20_000, "고객변심", 1L);

        // 카드 14,000 + 월렛 6,000 = 20,000 전액취소. 월렛 몫이 반드시 환불돼야 한다.
        verify(walletService).refund(1L, 6_000, order.getOrderNo());
        verify(paymentService).cancelByOrderNo(order.getOrderNo(), Money.krw(14_000), "고객변심");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(result.fullyCanceled()).isTrue();
        assertThat(result.refundedWallet()).isEqualTo(6_000);
        assertThat(result.refundedCard()).isEqualTo(14_000);
    }

    @Test
    @DisplayName("전액 월렛 결제 주문 취소: 카드 취소 없이 월렛 전액 환불 + CANCELED")
    void fullCancelWalletOnlyOrder() {
        Order order = paidOrderOf(100L, 2); // total 20,000
        when(pointService.refundableAmount(order.getOrderNo())).thenReturn(0L);
        when(walletService.refundableAmount(order.getOrderNo())).thenReturn(20_000L);
        when(paymentService.cardBalance(order.getOrderNo())).thenReturn(0L);

        CancelResult result = service.cancel(order.getOrderNo(), 20_000, "고객변심", 1L);

        verify(walletService).refund(1L, 20_000, order.getOrderNo());
        verify(paymentService, never()).cancelByOrderNo(anyString(), any(Money.class), anyString());
        assertThat(result.fullyCanceled()).isTrue();
        assertThat(result.refundedWallet()).isEqualTo(20_000);
    }

    @Test
    @DisplayName("전액취소(전액 포인트 결제, 카드잔액 0): 포인트만 환불, 카드 취소 미호출, 재고 복원, CANCELED")
    void fullCancelPointOnly() {
        Order order = paidOrderOf(100L, 2); // total 20,000
        when(pointService.refundableAmount(order.getOrderNo())).thenReturn(20_000L);
        when(paymentService.cardBalance(order.getOrderNo())).thenReturn(0L);

        CancelResult result = service.cancel(order.getOrderNo(), 20_000, "고객변심", 1L);

        verify(pointService).refund(1L, 20_000, order.getOrderNo());
        verify(paymentService, never()).cancelByOrderNo(anyString(), any(Money.class), anyString());
        verify(stockDeductionService).restore(100L, 2);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CANCELED);
        verify(orderRepository).saveAndFlush(order); // 전액취소 상태 전이 명시 영속
        assertThat(result.fullyCanceled()).isTrue();
        assertThat(result.refundedCard()).isZero();
    }

    @Test
    @DisplayName("부분취소(포인트 잔액 내): 포인트만 환불, 카드 미호출, 재고 복원 안 함, 주문 PAID 유지")
    void partialCancelWithinPoint() {
        Order order = paidOrderOf(100L, 2); // total 20,000
        when(pointService.refundableAmount(order.getOrderNo())).thenReturn(6_000L);
        when(paymentService.cardBalance(order.getOrderNo())).thenReturn(14_000L);

        CancelResult result = service.cancel(order.getOrderNo(), 5_000, "일부변심", 1L);

        verify(pointService).refund(1L, 5_000, order.getOrderNo());     // 포인트 잔액 내라 전액 포인트로
        verify(paymentService, never()).cancelByOrderNo(anyString(), any(Money.class), anyString());
        verify(stockDeductionService, never()).restore(anyLong(), anyInt()); // 부분취소 재고 복원 안 함
        verify(orderRepository, never()).save(any(Order.class)); // 부분취소는 상태 전이가 없어 저장도 없음
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);      // 주문 PAID 유지
        assertThat(result.fullyCanceled()).isFalse();
        assertThat(result.refundedPoint()).isEqualTo(5_000);
        assertThat(result.refundedCard()).isZero();
    }

    @Test
    @DisplayName("남의 주문을 취소하려 하면 ORDER_FORBIDDEN — 검증·환불 전에 차단")
    void rejectsNonOwner() {
        Order order = paidOrderOf(100L, 2); // 주인은 userId 1

        // userId 2가 주인이 1인 주문을 취소 시도
        assertThatThrownBy(() -> service.cancel(order.getOrderNo(), 20_000, "고객변심", 2L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_FORBIDDEN"));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID); // 상태 전이 없음
        verify(pointService, never()).refund(anyLong(), anyLong(), anyString());
        verify(paymentService, never()).cancelByOrderNo(anyString(), any(Money.class), anyString());
        verify(stockDeductionService, never()).restore(anyLong(), anyInt());
        // 소유권 검증 이전이라 잔액 조회조차 하지 않는다.
        verify(pointService, never()).refundableAmount(anyString());
        verify(paymentService, never()).cardBalance(anyString());
    }

    @Test
    @DisplayName("PAID 아닌 주문 취소는 INVALID_STATE_TRANSITION")
    void rejectsNonPaidOrder() {
        Order order = Order.create(1L, List.of(OrderItem.of(100L, "상품A", 10_000, 2)));
        // PENDING_PAYMENT 상태 그대로
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.cancel(order.getOrderNo(), 20_000, "고객변심", 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_STATE_TRANSITION"));

        verify(pointService, never()).refund(anyLong(), anyLong(), anyString());
        verify(paymentService, never()).cancelByOrderNo(anyString(), any(Money.class), anyString());
    }

    @Test
    @DisplayName("취소액이 잔여 결제분을 초과하면 CANCEL_AMOUNT_EXCEEDED")
    void rejectsExceedingAmount() {
        Order order = paidOrderOf(100L, 2); // total 20,000
        when(pointService.refundableAmount(order.getOrderNo())).thenReturn(6_000L);
        when(paymentService.cardBalance(order.getOrderNo())).thenReturn(14_000L); // 잔여 20,000

        assertThatThrownBy(() -> service.cancel(order.getOrderNo(), 25_000, "고객변심", 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("CANCEL_AMOUNT_EXCEEDED"));

        verify(pointService, never()).refund(anyLong(), anyLong(), anyString());
        verify(paymentService, never()).cancelByOrderNo(anyString(), any(Money.class), anyString());
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    @DisplayName("취소 금액이 0 이하면 INVALID_REQUEST — 주문 로드 전에 차단")
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> service.cancel("any", 0, "고객변심", 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_REQUEST"));

        verify(orderRepository, never()).findByOrderNo(anyString());
    }
}
