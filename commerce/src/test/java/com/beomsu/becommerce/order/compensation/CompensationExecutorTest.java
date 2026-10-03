package com.beomsu.becommerce.order.compensation;

import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.payment.internal.PaymentCancelTx;
import com.beomsu.becommerce.payment.internal.PaymentRepository;
import com.beomsu.becommerce.payment.pg.PgCancelCommand;
import com.beomsu.becommerce.payment.pg.PgClient;
import com.beomsu.becommerce.shared.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CompensationExecutorTest {

    private CompensationTaskRepository repository;
    private PaymentService paymentService;
    private SimpleMeterRegistry meterRegistry;
    private CompensationExecutor executor;

    @BeforeEach
    void setUp() {
        repository = mock(CompensationTaskRepository.class);
        paymentService = mock(PaymentService.class);
        meterRegistry = new SimpleMeterRegistry();
        executor = new CompensationExecutor(repository, paymentService, meterRegistry);
    }

    private CompensationTask pendingTask() {
        return CompensationTask.networkCancel("ord-1", 14_000, "재고 부족: 카드 승인 후 자동 망취소");
    }

    private double counter(String name, String outcome) {
        var c = meterRegistry.find(name).tag("outcome", outcome).counter();
        return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("attempt 성공: 망취소 호출 + 태스크 DONE + success 계측")
    void attemptSuccess() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        executor.attempt(1L);

        verify(paymentService).cancelByOrderNo("ord-1", Money.krw(14_000),
                "재고 부족: 카드 승인 후 자동 망취소");
        assertThat(task.getStatus()).isEqualTo(CompensationStatus.DONE);
        assertThat(counter("compensation.processed", "success")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PAYMENT_ALREADY_SETTLED: 결제는 있는데 취소 불가 상태 → 이미 보상됨으로 DONE")
    void attemptAlreadySettledIsIdempotentDone() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        doThrow(new PaymentException("PAYMENT_ALREADY_SETTLED", "이미 취소됐거나 취소할 수 없는 상태입니다: ord-1"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        executor.attempt(1L);

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.DONE);
        assertThat(counter("compensation.processed", "already")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PAYMENT_NOT_FOUND: 결제가 아예 없다 → 확정하지 않고 운영자에게 넘긴다")
    void attemptPaymentNotFoundIsEscalatedNotCompleted() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        doThrow(new PaymentException("PAYMENT_NOT_FOUND", "취소할 결제를 찾을 수 없습니다: ord-1"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        executor.attempt(1L);

        // 보상 태스크가 있다는 건 승인이 났다는 뜻이다. "없다"를 "이미 했다"로 읽으면 보상이 사라진다.
        assertThat(task.getStatus()).isEqualTo(CompensationStatus.FAILED);
        assertThat(counter("compensation.processed", "not_found")).isEqualTo(1.0);
        assertThat(meterRegistry.counter("compensation.exhausted").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PAYMENT_UNRESOLVED: 결제가 아직 미확정 → 보류(PENDING 유지, retryCount 불변, SKIPPED_UNRESOLVED)")
    void attemptUnresolvedHoldsPendingWithoutConsumingRetryBudget() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        doThrow(new PaymentException("PAYMENT_UNRESOLVED",
                "결제 결과가 확정되지 않아 취소할 수 없습니다(미확정 결제 존재): ord-1"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        Instant before = Instant.now();
        executor.attempt(1L);

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(task.getRetryCount()).isEqualTo(0);                 // 실패가 아니라 보류다
        assertThat(task.getLastOutcome()).isEqualTo(CompensationOutcome.SKIPPED_UNRESOLVED);
        assertThat(task.getNextAttemptAt()).isAfter(before);           // 다음 주기로 미룬다
        assertThat(counter("compensation.processed", "skipped_unresolved")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("읽기 타임아웃(전송 후 응답 미수신) → PENDING + lastOutcome=OUTCOME_UNKNOWN")
    void attemptReadTimeoutRecordsOutcomeUnknown() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        // 취소 읽기 타임아웃은 payment 모듈이 CANCEL_OUTCOME_UNKNOWN 으로 번역해 넘긴다(모듈 경계).
        doThrow(new PaymentException("CANCEL_OUTCOME_UNKNOWN", "Read timed out"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        executor.attempt(1L);

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(task.getLastOutcome()).isEqualTo(CompensationOutcome.OUTCOME_UNKNOWN);
        assertThat(counter("compensation.processed", "outcome_unknown")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("연결 실패(요청 바이트 미전송) → PENDING + lastOutcome=FAILED_DEFINITE")
    void attemptConnectFailureRecordsDefinite() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        // 연결 실패는 payment 모듈이 CANCEL_CONNECT_FAILED 로 번역해 넘긴다(모듈 경계).
        doThrow(new PaymentException("CANCEL_CONNECT_FAILED", "연결 거부"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        executor.attempt(1L);

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(task.getLastOutcome()).isEqualTo(CompensationOutcome.FAILED_DEFINITE);
        assertThat(counter("compensation.processed", "connect_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("취소 5xx 응답 → 미확정으로 번역되어 PENDING + OUTCOME_UNKNOWN (승인 쪽 5xx 규칙과 동일)")
    void attemptCancel5xxRecordsOutcomeUnknown() {
        // 실제 PaymentService 번역 경로를 태운다: PG 5xx → CANCEL_OUTCOME_UNKNOWN → 실행기가 미확정으로 기록.
        PaymentRepository paymentRepository = mock(PaymentRepository.class);
        PgClient pg = mock(PgClient.class);
        ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
        PaymentService realService = new PaymentService(paymentRepository, pg,
                new PaymentCancelTx(paymentRepository, events), events, new SimpleMeterRegistry());

        Payment payment = Payment.initiate("ord-9", Money.krw(10_000));
        payment.startApproval("pk-9");
        payment.approve("CARD");
        when(paymentRepository.findFirstByOrderNoAndStatusIn("ord-9", PaymentCancelTx.CANCELABLE))
                .thenReturn(Optional.of(payment));
        when(pg.cancel(any(PgCancelCommand.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR));

        CompensationTask task = CompensationTask.networkCancel("ord-9", 10_000, "재고 부족");
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        CompensationExecutor realExecutor = new CompensationExecutor(repository, realService, meterRegistry);

        realExecutor.attempt(1L);

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(task.getLastOutcome()).isEqualTo(CompensationOutcome.OUTCOME_UNKNOWN);
    }

    @Test
    @DisplayName("catch-all 실패(분류 불가) → lastOutcome 을 단정하지 않는다(null, 미분류)")
    void recordFailureCatchAllLeavesOutcomeNull() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        executor.recordFailure(1L, "분류할 수 없는 오류");

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.getLastOutcome()).isNull();
    }

    @Test
    @DisplayName("PAYMENT_APPROVAL_FAILED: 승인 실패 확정만 존재 → DONE, 사유에 '승인 실패'")
    void attemptApprovalFailedOnlyIsDoneWithReason() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        doThrow(new PaymentException("PAYMENT_APPROVAL_FAILED",
                "승인 실패 확정 — 취소할 원거래 없음: ord-1"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        executor.attempt(1L);

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.DONE);
        assertThat(task.getLastError()).contains("승인 실패");
        assertThat(counter("compensation.processed", "approval_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("PAYMENT_APPROVAL_FAILED(EXPIRED 등, 승인 실패 아님) → DONE, 사유에 실제 상태·'승인 실패' 없음")
    void attemptExpiredOnlyIsDoneWithoutApprovalFailedWording() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        doThrow(new PaymentException("PAYMENT_APPROVAL_FAILED",
                "승인된 원거래 없음(상태: EXPIRED): ord-1"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        executor.attempt(1L);

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.DONE);
        assertThat(task.getLastError()).contains("EXPIRED").doesNotContain("승인 실패");
        assertThat(counter("compensation.processed", "approval_failed")).isEqualTo(1.0);
    }

    @Test
    @DisplayName("그 외 예외: 그대로 전파(트랜잭션 롤백) + 태스크는 DONE되지 않음")
    void attemptOtherErrorPropagatesAndKeepsPending() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));
        doThrow(new RuntimeException("PG 취소 호출 실패"))
                .when(paymentService).cancelByOrderNo(anyString(), any(Money.class), anyString());

        assertThatThrownBy(() -> executor.attempt(1L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("PG 취소 호출 실패");

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.PENDING); // markDone 안 됨
    }

    @Test
    @DisplayName("이미 PENDING이 아니면 아무 것도 하지 않는다(멱등)")
    void attemptNonPendingIsNoop() {
        CompensationTask task = pendingTask();
        task.markDone();
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        executor.attempt(1L);

        verifyNoInteractions(paymentService);
    }

    @Test
    @DisplayName("recordFailure: retryCount++ · nextAttemptAt 미래 · status PENDING · save")
    void recordFailureBacksOffAndStaysPending() {
        CompensationTask task = pendingTask();
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        Instant before = Instant.now();
        executor.recordFailure(1L, "일시 오류");

        assertThat(task.getRetryCount()).isEqualTo(1);
        assertThat(task.getStatus()).isEqualTo(CompensationStatus.PENDING);
        assertThat(task.getNextAttemptAt()).isAfter(before);
        assertThat(task.getLastError()).isEqualTo("일시 오류");
        verify(repository).save(task);
    }

    @Test
    @DisplayName("recordFailure 소진: maxRetries 도달 시 FAILED + exhausted 계측")
    void recordFailureExhaustsToFailed() {
        CompensationTask task = pendingTask(); // maxRetries=5
        when(repository.findById(1L)).thenReturn(Optional.of(task));

        for (int i = 0; i < 5; i++) {
            executor.recordFailure(1L, "계속 실패");
        }

        assertThat(task.getStatus()).isEqualTo(CompensationStatus.FAILED);
        assertThat(task.isExhausted()).isTrue();
        assertThat(meterRegistry.find("compensation.exhausted").counter().count()).isEqualTo(1.0);
    }
}
