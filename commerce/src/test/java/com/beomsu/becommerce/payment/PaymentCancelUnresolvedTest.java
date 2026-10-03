package com.beomsu.becommerce.payment;

import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.internal.PaymentCancelTx;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.pg.PgCancelCommand;
import com.beomsu.becommerce.payment.pg.PgCancelResult;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.shared.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.client.ResourceAccessException;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 미확정(UNKNOWN·IN_PROGRESS)·승인실패(ABORTED) 결제의 취소 판정을 고정한다.
 *
 * <p>전이표는 UNKNOWN→CANCELED 를 허용하지만 그건 복구 배치가 PG 조회로 "이미 취소됨"을 확인했을 때
 * 쓰는 전이다. 사용자·운영자발 취소가 그 전이에 기대면, PG 취소가 실제로 나간 뒤 장부는 UNKNOWN 으로
 * 남는다 — 그래서 PG 호출 <b>앞에서</b> 거부해야 한다.
 */
class PaymentCancelUnresolvedTest {

    private PaymentRepository repository;
    private PgClient pg;
    private PaymentCancelTx cancelTx;
    private PaymentService service;

    @BeforeEach
    void setUp() {
        repository = mock(PaymentRepository.class);
        pg = mock(PgClient.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        cancelTx = new PaymentCancelTx(repository, events);   // 실제 조회·검증 경로를 태운다
        service = new PaymentService(repository, pg, cancelTx, events, new SimpleMeterRegistry());
    }

    private Payment unknown(String paymentKey) {
        Payment p = Payment.initiate("ord-1", Money.krw(10_000));
        p.startApproval(paymentKey);
        p.markUnknown("PG 응답 타임아웃");
        return p;
    }

    private Payment done(String orderNo, String paymentKey) {
        Payment p = Payment.initiate(orderNo, Money.krw(10_000));
        p.startApproval(paymentKey);
        p.approve("CARD");
        return p;
    }

    private Payment aborted(String paymentKey) {
        Payment p = Payment.initiate("ord-x", Money.krw(10_000));
        p.startApproval(paymentKey);
        p.abort("카드 거절");
        return p;
    }

    @Test
    @DisplayName("UNKNOWN 결제 강제취소(전액) → PAYMENT_UNRESOLVED, PgClient.cancel 미호출")
    void forceCancelOfUnknownIsRejectedBeforePg() {
        when(repository.findById(9L)).thenReturn(Optional.of(unknown("pk-1")));

        assertThatThrownBy(() -> service.cancel(9L, Money.krw(10_000), "강제취소"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_UNRESOLVED"));

        verify(pg, never()).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("IN_PROGRESS 결제 강제취소 → PAYMENT_UNRESOLVED, PgClient.cancel 미호출")
    void forceCancelOfInProgressIsRejectedBeforePg() {
        Payment p = Payment.initiate("ord-1", Money.krw(10_000));
        p.startApproval("pk-2"); // IN_PROGRESS
        when(repository.findById(9L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.cancel(9L, Money.krw(10_000), "강제취소"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_UNRESOLVED"));

        verify(pg, never()).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("UNKNOWN 결제가 있는 주문의 취소 → PAYMENT_UNRESOLVED, PgClient.cancel 미호출")
    void orderCancelOfUnknownIsRejectedBeforePg() {
        when(repository.findFirstByOrderNoAndStatusIn("ord-1", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.empty());
        when(repository.existsByOrderNo("ord-1")).thenReturn(true);
        when(repository.findFirstByOrderNoAndStatusIn("ord-1",
                List.of(PaymentStatus.UNKNOWN, PaymentStatus.IN_PROGRESS)))
                .thenReturn(Optional.of(unknown("pk-1")));

        assertThatThrownBy(() -> service.cancelByOrderNo("ord-1", Money.krw(10_000), "고객변심"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_UNRESOLVED"));

        verify(pg, never()).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("ABORTED만 있는 주문의 취소 → PAYMENT_APPROVAL_FAILED(사유에 '승인 실패')")
    void orderCancelOfAbortedOnlyIsApprovalFailed() {
        when(repository.findFirstByOrderNoAndStatusIn("ord-2", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.empty());
        when(repository.existsByOrderNo("ord-2")).thenReturn(true);
        when(repository.findFirstByOrderNoAndStatusIn("ord-2",
                List.of(PaymentStatus.UNKNOWN, PaymentStatus.IN_PROGRESS)))
                .thenReturn(Optional.empty());
        when(repository.findFirstByOrderNoAndStatusIn("ord-2", List.of(PaymentStatus.CANCELED)))
                .thenReturn(Optional.empty());
        when(repository.findFirstByOrderNoAndStatusIn("ord-2", List.of(PaymentStatus.ABORTED)))
                .thenReturn(Optional.of(aborted("pk-2")));

        assertThatThrownBy(() -> service.cancelByOrderNo("ord-2", Money.krw(10_000), "고객변심"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> {
                    assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_APPROVAL_FAILED");
                    assertThat(e.getMessage()).contains("승인 실패");
                });

        verify(pg, never()).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("EXPIRED만 있는 주문 → PAYMENT_APPROVAL_FAILED, 문구에 '승인 실패' 없이 실제 상태(EXPIRED) 포함")
    void orderCancelOfExpiredOnlyIsDoneWithoutApprovalFailedWording() {
        when(repository.findFirstByOrderNoAndStatusIn("ord-7", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.empty());
        when(repository.existsByOrderNo("ord-7")).thenReturn(true);
        when(repository.findFirstByOrderNoAndStatusIn("ord-7",
                List.of(PaymentStatus.UNKNOWN, PaymentStatus.IN_PROGRESS)))
                .thenReturn(Optional.empty());
        when(repository.findFirstByOrderNoAndStatusIn("ord-7", List.of(PaymentStatus.CANCELED)))
                .thenReturn(Optional.empty());
        when(repository.findFirstByOrderNoAndStatusIn("ord-7", List.of(PaymentStatus.ABORTED)))
                .thenReturn(Optional.empty());
        // EXPIRED 로 만드는 도메인 메서드는 없다(READY→EXPIRED 전이는 있으나 호출부가 없다).
        // notCancelable 은 상태를 조회 결과로 판정하므로, 조회가 EXPIRED 행을 가리키게 스텁한다.
        when(repository.findFirstByOrderNoAndStatusIn("ord-7", List.of(PaymentStatus.EXPIRED)))
                .thenReturn(Optional.of(Payment.initiate("ord-7", Money.krw(10_000))));

        assertThatThrownBy(() -> service.cancelByOrderNo("ord-7", Money.krw(10_000), "고객변심"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> {
                    assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_APPROVAL_FAILED");
                    assertThat(e.getMessage()).contains("EXPIRED").doesNotContain("승인 실패");
                });

        verify(pg, never()).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("설명되지 않는 상태 조합(READY·ABORTED·EXPIRED 아님) → 조용히 닫지 않고 PAYMENT_UNRESOLVED 보류")
    void orderCancelOfUnexpectedStateIsHeldUnresolved() {
        when(repository.findFirstByOrderNoAndStatusIn("ord-8", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.empty());
        when(repository.existsByOrderNo("ord-8")).thenReturn(true);
        when(repository.findFirstByOrderNoAndStatusIn("ord-8",
                List.of(PaymentStatus.UNKNOWN, PaymentStatus.IN_PROGRESS)))
                .thenReturn(Optional.empty());
        when(repository.findFirstByOrderNoAndStatusIn("ord-8", List.of(PaymentStatus.CANCELED)))
                .thenReturn(Optional.empty());
        // READY·ABORTED·EXPIRED 조회가 모두 비어 있다 → 우리가 아는 사유로 설명되지 않는다.

        assertThatThrownBy(() -> service.cancelByOrderNo("ord-8", Money.krw(10_000), "고객변심"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_UNRESOLVED"));

        verify(pg, never()).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("이미 취소 확정된 주문 → PAYMENT_ALREADY_SETTLED(보상 완료로 읽힌다)")
    void orderCancelOfCanceledIsAlreadySettled() {
        when(repository.findFirstByOrderNoAndStatusIn("ord-3", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.empty());
        when(repository.existsByOrderNo("ord-3")).thenReturn(true);
        when(repository.findFirstByOrderNoAndStatusIn("ord-3",
                List.of(PaymentStatus.UNKNOWN, PaymentStatus.IN_PROGRESS)))
                .thenReturn(Optional.empty());
        when(repository.findFirstByOrderNoAndStatusIn("ord-3", List.of(PaymentStatus.CANCELED)))
                .thenReturn(Optional.of(unknown("pk-3")));

        assertThatThrownBy(() -> service.cancelByOrderNo("ord-3", Money.krw(10_000), "고객변심"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("PAYMENT_ALREADY_SETTLED"));

        verify(pg, never()).cancel(any(PgCancelCommand.class));
    }

    @Test
    @DisplayName("DONE 결제는 그대로 취소된다 — 가드가 정상 경로를 막지 않는다")
    void donePaymentStillCancels() {
        Payment p = done("ord-4", "pk-4");
        when(repository.findById(4L)).thenReturn(Optional.of(p));
        when(repository.findByPaymentKey("pk-4")).thenReturn(Optional.of(p));
        when(pg.cancel(any(PgCancelCommand.class))).thenReturn(new PgCancelResult("pg-tx-4"));

        service.cancel(4L, Money.krw(10_000), "정상취소");

        verify(pg).cancel(any(PgCancelCommand.class));
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("취소 읽기 타임아웃(전송 후 응답 미수신) → CANCEL_OUTCOME_UNKNOWN 으로 번역")
    void readTimeoutIsTranslatedToOutcomeUnknown() {
        Payment p = done("ord-5", "pk-5");
        when(repository.findFirstByOrderNoAndStatusIn("ord-5", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.of(p));
        when(pg.cancel(any(PgCancelCommand.class))).thenThrow(
                new ResourceAccessException("Read timed out", new SocketTimeoutException("Read timed out")));

        assertThatThrownBy(() -> service.cancelByOrderNo("ord-5", Money.krw(5_000), "취소"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("CANCEL_OUTCOME_UNKNOWN"));
    }

    @Test
    @DisplayName("취소 connect 실패(요청 바이트 미전송) → CANCEL_CONNECT_FAILED 로 번역")
    void connectFailureIsTranslatedToConnectFailed() {
        Payment p = done("ord-6", "pk-6");
        when(repository.findFirstByOrderNoAndStatusIn("ord-6", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.of(p));
        when(pg.cancel(any(PgCancelCommand.class))).thenThrow(
                new ResourceAccessException("연결 거부", new ConnectException("Connection refused")));

        assertThatThrownBy(() -> service.cancelByOrderNo("ord-6", Money.krw(5_000), "취소"))
                .isInstanceOf(PaymentException.class)
                .satisfies(e -> assertThat(((PaymentException) e).code()).isEqualTo("CANCEL_CONNECT_FAILED"));
    }
}
