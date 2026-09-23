package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.settlement.internal.SettlementService;
import com.beomsu.becommerce.settlement.internal.PaymentSettlementListener;
import com.beomsu.becommerce.escrow.EscrowReleasedEvent;
import com.beomsu.becommerce.payment.PaymentCanceledEvent;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.mockito.Mockito.*;

class PaymentSettlementListenerTest {

    private SettlementService service;
    private PaymentSettlementListener listener;

    @BeforeEach
    void setUp() {
        service = mock(SettlementService.class);
        listener = new PaymentSettlementListener(service);
    }

    @Test
    @DisplayName("승인 이벤트 → registerConfirmedPayment 위임")
    void onConfirmedDelegates() {
        PaymentConfirmedEvent event =
                new PaymentConfirmedEvent("order-1", 100L, 10_000, Instant.parse("2026-07-05T09:00:00Z"));

        listener.onConfirmed(event);

        verify(service).registerConfirmedPayment(event);
    }

    @Test
    @DisplayName("에스크로 릴리스 이벤트 → confirmSettlement(orderNo, 릴리스일) 위임")
    void onEscrowReleasedDelegates() {
        EscrowReleasedEvent event =
                new EscrowReleasedEvent("order-1", 10_000, Instant.parse("2026-07-06T09:00:00Z"));

        listener.onEscrowReleased(event);

        // 릴리스 시각의 UTC 날짜를 집계 기준일로 넘긴다.
        verify(service).confirmSettlement("order-1", LocalDate.of(2026, 7, 6));
    }

    @Test
    @DisplayName("취소 이벤트 → reflectCancellation 위임")
    void onCanceledDelegates() {
        PaymentCanceledEvent event = new PaymentCanceledEvent("order-1", 100L, 1, 10_000, 0, true, java.time.Instant.now());

        listener.onCanceled(event);

        verify(service).reflectCancellation(event);
    }
}
