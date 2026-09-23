package com.beomsu.becommerce.order.compensation;

import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.shared.Money;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
