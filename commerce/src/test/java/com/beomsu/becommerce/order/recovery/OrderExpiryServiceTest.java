package com.beomsu.becommerce.order.recovery;

import org.springframework.data.domain.Pageable;

import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderItem;
import com.beomsu.becommerce.order.internal.Order;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class OrderExpiryServiceTest {

    private OrderRepository orderRepository;
    private OrderExpiryService service;

    @BeforeEach
    void setUp() {
        orderRepository = mock(OrderRepository.class);
        service = new OrderExpiryService(orderRepository,
                org.mockito.Mockito.mock(com.beomsu.becommerce.order.catalog.StockReservationService.class));
    }

    /** PENDING_PAYMENT 상태의 실제 주문 하나. */
    private Order pendingOrder() {
        return Order.create(1L, List.of(OrderItem.of(10L, "상품", 10_000, 1)));
    }

    @Test
    @DisplayName("만료 대상 각 건을 EXPIRED로 전이하고 saveAndFlush로 명시 영속, 처리 수 반환")
    void expiresEachTargetAndFlushes() {
        Order o1 = pendingOrder();
        Order o2 = pendingOrder();
        when(orderRepository.findByStatusAndExpiresAtBefore(eq(OrderStatus.PENDING_PAYMENT), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(o1, o2));

        int processed = service.expireOverdue(Instant.now());

        assertThat(processed).isEqualTo(2);
        assertThat(o1.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(o2.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        // OSIV off에서 dirty-checking 자동 flush에 의존하지 않고 명시 영속한다.
        verify(orderRepository).saveAndFlush(o1);
        verify(orderRepository).saveAndFlush(o2);
    }

    @Test
    @DisplayName("한 건 실패가 배치를 멈추지 않는다 — 나머지는 계속 처리하고 성공 수만 센다")
    void isolatesPerItemFailure() {
        Order ok = pendingOrder();
        Order bad = mock(Order.class);
        when(bad.getOrderNo()).thenReturn("ord-bad");
        doThrow(new IllegalStateException("전이 불가")).when(bad).markExpired();
        when(orderRepository.findByStatusAndExpiresAtBefore(eq(OrderStatus.PENDING_PAYMENT), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(bad, ok));

        int processed = service.expireOverdue(Instant.now());

        assertThat(processed).isEqualTo(1);
        assertThat(ok.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        verify(orderRepository).saveAndFlush(ok);
        verify(orderRepository, never()).saveAndFlush(bad);
    }

    @Test
    @DisplayName("만료 대상이 없으면 0을 반환하고 아무것도 저장하지 않는다")
    void noTargetsNoWork() {
        when(orderRepository.findByStatusAndExpiresAtBefore(eq(OrderStatus.PENDING_PAYMENT), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        assertThat(service.expireOverdue(Instant.now())).isZero();
        verify(orderRepository, never()).saveAndFlush(any());
    }
}
