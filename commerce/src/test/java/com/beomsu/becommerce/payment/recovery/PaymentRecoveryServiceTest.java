package com.beomsu.becommerce.payment.recovery;

import org.springframework.data.domain.Pageable;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.PaymentConfirmedEvent;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.payment.pg.PgPaymentStatus;
import com.beomsu.becommerce.payment.pg.PgQueryResult;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentRecoveryServiceTest {

    private PaymentRepository repository;
    private PgClient pg;
    private ApplicationEventPublisher events;
    private PaymentRecoveryService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        pg = mock(PgClient.class);
        events = mock(ApplicationEventPublisher.class);
        service = new PaymentRecoveryService(repository, pg, events);
    }

    /** UNKNOWN 상태로 방치된 결제 하나를 만들어 리포지토리가 돌려주게 한다. */
    private Payment unknownPayment(String paymentKey) {
        Payment p = Payment.initiate("order-1", Money.krw(10_000));
        p.startApproval(paymentKey);
        p.markUnknown("PG 응답 타임아웃");
        when(repository.findByStatusAndRequestedAtBefore(eq(PaymentStatus.UNKNOWN), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(p));
        return p;
    }

    @Test
    @DisplayName("PG에 승인돼 있으면 전진 복구(DONE) + 완료 이벤트 발행")
    void recoverForwardWhenPgApproved() {
        Payment p = unknownPayment("pk-1");
        when(pg.query("pk-1", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.APPROVED, "CARD"));

        int recovered = service.recoverUnknownPayments();

        assertThat(recovered).isEqualTo(1);
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(p.getMethod()).isEqualTo("CARD");
        // 복구 상태 전이가 명시 saveAndFlush로 영속된다(OSIV off에서 dirty-checking 자동 flush에 의존하지 않음).
        verify(repository).saveAndFlush(p);
        verify(events).publishEvent(any(PaymentConfirmedEvent.class));
    }

    @Test
    @DisplayName("PG에 결제가 없으면 ABORTED (승인이 실제로 안 됨), 이벤트 미발행")
    void abortWhenPgNotFound() {
        Payment p = unknownPayment("pk-2");
        when(pg.query("pk-2", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.NOT_FOUND, null));

        service.recoverUnknownPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.ABORTED);
        verify(repository).saveAndFlush(p); // 복구 상태 전이 명시 영속
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("PG에서 이미 취소됐으면 CANCELED(망취소 반영)")
    void networkCancelWhenPgCanceled() {
        Payment p = unknownPayment("pk-3");
        when(pg.query("pk-3", "TOSS_PAYMENTS")).thenReturn(new PgQueryResult(PgPaymentStatus.CANCELED, null));

        service.recoverUnknownPayments();

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELED);
        verify(repository).saveAndFlush(p); // 복구 상태 전이 명시 영속
    }
}
