package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.order.internal.OrderSummaryView;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderQueryService;
import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.OrderException;
import com.beomsu.becommerce.order.internal.OrderDetailView;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.payment.PaymentDetailView;
import com.beomsu.becommerce.payment.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OrderQueryServiceTest {

    private OrderRepository orderRepository;
    private PaymentService paymentService;
    private OrderQueryService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        paymentService = mock(PaymentService.class);
        service = new OrderQueryService(orderRepository, paymentService);
    }

    /** userId 1 소유의 10,000 x 2 = 20,000 짜리 PAID 주문. */
    private Order paidOrderOf(long userId) {
        Order order = Order.create(userId, List.of(OrderItem.of(100L, "상품A", 10_000, 2)));
        order.startPayment();
        order.markPaid();
        when(orderRepository.findByOrderNo(order.getOrderNo())).thenReturn(Optional.of(order));
        return order;
    }

    // --- myOrders (내 주문 목록) ---

    @Test
    @DisplayName("내 주문 목록: 본인 userId로만 조회해 요약 뷰로 매핑한다(IDOR — 쿼리 자체가 본인 것만)")
    void myOrdersReturnsOwnSummaries() {
        Order o = paidOrderOf(1L);
        when(orderRepository.findTop50ByUserIdOrderByIdDesc(1L)).thenReturn(List.of(o));

        List<OrderSummaryView> result = service.myOrders(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).orderNo()).isEqualTo(o.getOrderNo());
        assertThat(result.get(0).status()).isEqualTo("PAID");
        assertThat(result.get(0).totalAmount()).isEqualTo(20_000);
        verify(orderRepository).findTop50ByUserIdOrderByIdDesc(1L); // 본인 것만 가져옴
    }

    // --- getOrder ---

    @Test
    @DisplayName("주문 조회 정상: 항목 매핑 + 대표 결제 상태 포함")
    void getOrderOk() {
        Order order = paidOrderOf(1L);
        when(paymentService.paymentStatusByOrderNo(order.getOrderNo())).thenReturn(Optional.of("DONE"));

        OrderDetailView view = service.getOrder(order.getOrderNo(), 1L);

        assertThat(view.orderNo()).isEqualTo(order.getOrderNo());
        assertThat(view.status()).isEqualTo("PAID");
        assertThat(view.totalAmount()).isEqualTo(20_000);
        assertThat(view.paymentStatus()).isEqualTo("DONE");
        assertThat(view.items()).singleElement().satisfies(i -> {
            assertThat(i.productId()).isEqualTo(100L);
            assertThat(i.productName()).isEqualTo("상품A");
            assertThat(i.unitPrice()).isEqualTo(10_000);
            assertThat(i.quantity()).isEqualTo(2);
        });
    }

    @Test
    @DisplayName("주문 조회: 결제 시도가 없으면 paymentStatus는 null")
    void getOrderNoPayment() {
        Order order = paidOrderOf(1L);
        when(paymentService.paymentStatusByOrderNo(order.getOrderNo())).thenReturn(Optional.empty());

        OrderDetailView view = service.getOrder(order.getOrderNo(), 1L);

        assertThat(view.paymentStatus()).isNull();
    }

    @Test
    @DisplayName("주문 조회 소유권 위반: 403 ORDER_FORBIDDEN + 결제 상태 조회 미호출")
    void getOrderForbidden() {
        Order order = paidOrderOf(1L); // 주인은 userId 1

        assertThatThrownBy(() -> service.getOrder(order.getOrderNo(), 2L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_FORBIDDEN"));

        // 소유권 검증이 먼저라 남의 결제 존재조차 조회하지 않는다.
        verify(paymentService, never()).paymentStatusByOrderNo(anyString());
    }

    @Test
    @DisplayName("주문 조회: 없는 주문은 404 ORDER_NOT_FOUND")
    void getOrderNotFound() {
        when(orderRepository.findByOrderNo("no-such")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOrder("no-such", 1L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_NOT_FOUND"));
    }

    // --- getPayment ---

    @Test
    @DisplayName("결제 조회 정상: 소유권 검증 후 결제 상세 반환")
    void getPaymentOk() {
        Order order = paidOrderOf(1L);
        PaymentDetailView detail = new PaymentDetailView(7L, order.getOrderNo(), "DONE",
                20_000, 20_000, List.of(), List.of());
        when(paymentService.orderNoOf(7L)).thenReturn(Optional.of(order.getOrderNo()));
        when(paymentService.getDetail(7L)).thenReturn(Optional.of(detail));

        PaymentDetailView view = service.getPayment(7L, 1L);

        assertThat(view).isSameAs(detail);
    }

    @Test
    @DisplayName("결제 조회 소유권 위반(남의 결제): 403 ORDER_FORBIDDEN + 상세 조회 미호출")
    void getPaymentForbidden() {
        Order order = paidOrderOf(1L); // 주인은 userId 1
        when(paymentService.orderNoOf(7L)).thenReturn(Optional.of(order.getOrderNo()));

        assertThatThrownBy(() -> service.getPayment(7L, 2L))
                .isInstanceOf(OrderException.class)
                .satisfies(e -> assertThat(((OrderException) e).code()).isEqualTo("ORDER_FORBIDDEN"));

        verify(paymentService, never()).getDetail(anyLong());
    }

    @Test
    @DisplayName("결제 조회: 없는 결제는 404 PAYMENT_NOT_FOUND")
    void getPaymentNotFound() {
        when(paymentService.orderNoOf(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getPayment(99L, 1L))
                .isInstanceOf(com.beomsu.becommerce.payment.PaymentException.class)
                .satisfies(e -> assertThat(((com.beomsu.becommerce.payment.PaymentException) e).code())
                        .isEqualTo("PAYMENT_NOT_FOUND"));
    }
}
