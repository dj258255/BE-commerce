package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.internal.UnknownPaymentView;
import com.beomsu.becommerce.payment.internal.PaymentSyncView;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.internal.PaymentAdminService;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.recovery.PaymentRecoveryService;
import com.beomsu.becommerce.shared.Money;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class PaymentAdminServiceTest {

    private PaymentRepository paymentRepository;
    private PaymentRecoveryService recoveryService;
    private PaymentAdminService service;

    @BeforeEach
    void setUp() {
        paymentRepository = mock(PaymentRepository.class);
        recoveryService = mock(PaymentRecoveryService.class);
        service = new PaymentAdminService(paymentRepository, recoveryService);
    }

    private Payment unknownPayment() {
        Payment p = Payment.initiate("ord-1", Money.krw(14_000));
        p.startApproval("pk-1");
        p.markUnknown("PG 타임아웃");
        return p;
    }

    private Payment donePayment() {
        Payment p = Payment.initiate("ord-1", Money.krw(14_000));
        p.startApproval("pk-1");
        p.approve("CARD");
        return p;
    }

    @Test
    @DisplayName("listUnknown: UNKNOWN 결제 페이지를 뷰로 매핑한다")
    void listUnknownMapsToView() {
        Payment p = unknownPayment();
        Pageable pageable = PageRequest.of(0, 20);
        when(paymentRepository.findByStatus(eq(PaymentStatus.UNKNOWN), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(p), pageable, 1));

        Page<UnknownPaymentView> views = service.listUnknown(pageable);

        assertThat(views).hasSize(1);
        UnknownPaymentView v = views.getContent().get(0);
        assertThat(v.orderNo()).isEqualTo("ord-1");
        assertThat(v.amount()).isEqualTo(14_000);
        assertThat(v.status()).isEqualTo(PaymentStatus.UNKNOWN);
        assertThat(v.requestedAt()).isNotNull();
    }

    @Test
    @DisplayName("recover: 복구 서비스에 위임하고 처리 건수를 반환한다")
    void recoverDelegatesAndReturnsCount() {
        when(recoveryService.recoverUnknownPayments()).thenReturn(3);

        int recovered = service.recover();

        assertThat(recovered).isEqualTo(3);
        verify(recoveryService).recoverUnknownPayments();
    }

    @Test
    @DisplayName("sync: 복구 서비스에 위임(PG 조회)하고 재조회한 확정 상태를 반환한다")
    void syncDelegatesToRecoveryAndReturnsRefreshedStatus() {
        long paymentId = 42L;
        // 첫 조회는 UNKNOWN(방치 상태), resolveByPaymentKey가 PG 조회로 확정한 뒤
        // 두 번째 조회는 DONE — 재조회로 최신 상태를 응답함을 검증한다.
        when(paymentRepository.findById(paymentId))
                .thenReturn(Optional.of(unknownPayment()), Optional.of(donePayment()));

        PaymentSyncView view = service.sync(paymentId);

        assertThat(view.orderNo()).isEqualTo("ord-1");
        assertThat(view.status()).isEqualTo("DONE");
        verify(recoveryService).resolveByPaymentKey("pk-1");
        verify(paymentRepository, times(2)).findById(paymentId);
    }

    @Test
    @DisplayName("sync: 없는 결제 id면 PAYMENT_NOT_FOUND")
    void syncThrowsWhenNotFound() {
        long paymentId = 999L;
        when(paymentRepository.findById(paymentId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.sync(paymentId))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_NOT_FOUND"));
        verify(recoveryService, never()).resolveByPaymentKey(anyString());
    }
}
