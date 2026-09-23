package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.PurchaseConfirmationService;
import com.beomsu.becommerce.order.internal.PurchaseConfirmationResult;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.OrderException;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.escrow.EscrowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class PurchaseConfirmationServiceTest {

    private OrderRepository orderRepository;
    private EscrowService escrowService;
    private PurchaseConfirmationService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        escrowService = mock(EscrowService.class);
        service = new PurchaseConfirmationService(orderRepository, escrowService);
    }

    /** userId 1 소유의 PAID 주문. */
    private Order paidOrder() {
        Order order = Order.create(1L, List.of(OrderItem.of(100L, "상품A", 10_000, 2)));
        order.startPayment();
        order.markPaid();
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    @DisplayName("정상: 소유권·PAID 검증 후 escrowService.release 위임")
    void confirmsAndDelegates() {
        Order order = paidOrder();

        PurchaseConfirmationResult result = service.confirmPurchase(order.getOrderNo(), 1L);

        verify(escrowService).release(order.getOrderNo());
        assertThat(result.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(result.message()).contains("구매확정");
    }

    @Test
    @DisplayName("소유권 위반: ORDER_FORBIDDEN, escrowService.release 미호출")
    void rejectsNonOwner() {
        Order order = paidOrder(); // 주인은 userId 1

        assertThatThrownBy(() -> service.confirmPurchase(order.getOrderNo(), 2L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_FORBIDDEN"));

        verify(escrowService, never()).release(anyString());
    }

    @Test
    @DisplayName("PAID 아님: INVALID_STATE_TRANSITION, escrowService.release 미호출")
    void rejectsNonPaid() {
        Order order = Order.create(1L, List.of(OrderItem.of(100L, "상품A", 10_000, 2)));
        // PENDING_PAYMENT 상태 그대로
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));

        assertThatThrownBy(() -> service.confirmPurchase(order.getOrderNo(), 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("INVALID_STATE_TRANSITION"));

        verify(escrowService, never()).release(anyString());
    }

    @Test
    @DisplayName("주문 없음: ORDER_NOT_FOUND, escrowService.release 미호출")
    void rejectsMissingOrder() {
        when(orderRepository.findByOrderNo("ord-x")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirmPurchase("ord-x", 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_NOT_FOUND"));

        verify(escrowService, never()).release(anyString());
    }
}
