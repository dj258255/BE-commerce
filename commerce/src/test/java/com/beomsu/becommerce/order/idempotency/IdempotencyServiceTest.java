package com.beomsu.becommerce.order.idempotency;

import com.beomsu.becommerce.order.internal.CheckoutResult;
import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.payment.PaymentStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class IdempotencyServiceTest {

    private static final String KEY = "idem-key-1";
    private static final String PATH = "/api/v1/payments/confirm";
    private static final Map<String, Object> REQUEST = Map.of("orderNo", "o1", "amount", 20_000);

    private IdempotencyRepository repository;
    private ObjectMapper mapper;
    private SimpleMeterRegistry meterRegistry;
    private IdempotencyService service;

    @BeforeEach
    void setUp() {
        repository = mock(IdempotencyRepository.class);
        mapper = new ObjectMapper().findAndRegisterModules();
        meterRegistry = new SimpleMeterRegistry();
        service = new IdempotencyService(repository, mapper, meterRegistry);
    }

    private CheckoutResult cached() {
        return new CheckoutResult("o1", OrderStatus.PAID, PaymentStatus.DONE, "승인 완료");
    }

    private Supplier<CheckoutResult> actionReturning(CheckoutResult r, AtomicInteger calls) {
        return () -> { calls.incrementAndGet(); return r; };
    }

    private String hashOf(Object req) throws Exception {
        byte[] h = MessageDigest.getInstance("SHA-256")
                .digest(mapper.writeValueAsString(req).getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(h);
    }

    @Test
    @DisplayName("신규 키: action을 실행하고 응답을 DONE으로 저장한다")
    void newKeyExecutesAndStores() {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        CheckoutResult result = service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        verify(repository).save(argThat(r -> r.isDone()));
    }

    @Test
    @DisplayName("완료된 같은 키: action을 다시 실행하지 않고 첫 응답을 재반환한다 (따닥 방지)")
    void doneKeyReplaysWithoutExecuting() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IdempotencyRecord done = IdempotencyRecord.start(KEY, PATH, "POST", hashOf(REQUEST));
        done.complete(mapper.writeValueAsString(cached()));
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(done));

        CheckoutResult result = service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls));

        assertThat(calls.get()).isZero();                     // 실제 결제 재실행 없음
        assertThat(result.orderNo()).isEqualTo("o1");
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(meterRegistry.counter("idempotency.replay").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("처리 중인 같은 키: 409 IDEMPOTENT_REQUEST_PROCESSING")
    void processingKeyRejectsWith409() throws Exception {
        IdempotencyRecord processing = IdempotencyRecord.start(KEY, PATH, "POST", hashOf(REQUEST));
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(processing));

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), new AtomicInteger())))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("IDEMPOTENT_REQUEST_PROCESSING"));
        assertThat(meterRegistry.counter("idempotency.processing").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("같은 키 + 다른 본문: 422 IDEMPOTENCY_KEY_REUSED")
    void sameKeyDifferentBodyRejectsWith422() {
        IdempotencyRecord other = IdempotencyRecord.start(KEY, PATH, "POST", "다른요청의해시");
        other.complete("{}");
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), new AtomicInteger())))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("IDEMPOTENCY_KEY_REUSED"));
        assertThat(meterRegistry.counter("idempotency.conflict").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("멱등키 누락: 400 INVALID_IDEMPOTENCY_KEY")
    void missingKeyRejectsWith400() {
        assertThatThrownBy(() -> service.execute("  ", PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), new AtomicInteger())))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("INVALID_IDEMPOTENCY_KEY"));
    }

    // --- 데드락(transient) 재시도 ---

    private double retryCount() {
        return meterRegistry.counter("idempotency.deadlock.retry").count();
    }

    @Test
    @DisplayName("데드락 2회 후 성공: 재시도로 흡수하고 결과 반환 + DONE 저장 + 레코드 미삭제")
    void deadlockTwiceThenSucceeds() {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        Supplier<CheckoutResult> action = () -> {
            if (calls.incrementAndGet() <= 2) {
                throw new CannotAcquireLockException("Deadlock found when trying to get lock");
            }
            return cached();
        };

        CheckoutResult result = service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class, action);

        assertThat(calls.get()).isEqualTo(3);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(retryCount()).isEqualTo(2.0);
        verify(repository).save(argThat(IdempotencyRecord::isDone));
        verify(repository, never()).delete(any());
    }

    @Test
    @DisplayName("데드락 3회 전부 실패: 예외 전파 + 레코드 삭제(클라이언트 재시도 가능) + 카운터 2회")
    void deadlockExhaustsRetries() {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        Supplier<CheckoutResult> action = () -> {
            calls.incrementAndGet();
            throw new CannotAcquireLockException("Deadlock found when trying to get lock");
        };

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class, action))
                .isInstanceOf(CannotAcquireLockException.class);

        assertThat(calls.get()).isEqualTo(3);
        assertThat(retryCount()).isEqualTo(2.0);          // 마지막 시도 전까지만 카운트
        verify(repository).delete(any(IdempotencyRecord.class));
        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("일반 RuntimeException: 재시도 없이 즉시 전파 + 레코드 삭제 (기존 동작 불변)")
    void nonDeadlockFailureIsNotRetried() {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        Supplier<CheckoutResult> action = () -> {
            calls.incrementAndGet();
            throw new IllegalStateException("일반 실패");
        };

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class, action))
                .isInstanceOf(IllegalStateException.class);

        assertThat(calls.get()).isEqualTo(1);
        assertThat(retryCount()).isZero();
        verify(repository).delete(any(IdempotencyRecord.class));
    }

    // --- 처리권 만료(#369) ---

    /** 처리하던 요청이 죽어 처리권이 만료된 PROCESSING 레코드. 10분 전에 3분 리스로 시작했다. */
    private IdempotencyRecord expiredProcessing() throws Exception {
        return IdempotencyRecord.start(KEY, PATH, "POST", hashOf(REQUEST),
                Instant.now().minus(Duration.ofMinutes(10)), Duration.ofMinutes(3));
    }

    @Test
    @DisplayName("처리권이 만료된 같은 키: 넘겨받아 한 번 다시 실행하고 DONE 으로 저장한다(#369)")
    void expiredLeaseIsTakenOverAndReExecuted() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        IdempotencyRecord stuck = expiredProcessing();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(stuck));
        when(repository.takeOverExpiredLease(any(), anyLong(), any(), any(), any())).thenReturn(1);
        when(repository.findById(any())).thenReturn(Optional.of(stuck));

        CheckoutResult result = service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls));

        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        verify(repository).save(argThat(r -> r.isDone()));
        assertThat(meterRegistry.counter("idempotency.lease.takeover").count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("만료된 처리권을 다른 요청이 먼저 넘겨받았으면: 실행하지 않고 409(#369)")
    void lostTakeoverRaceIsProcessing() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(expiredProcessing()));
        when(repository.takeOverExpiredLease(any(), anyLong(), any(), any(), any())).thenReturn(0);

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls)))
                .isInstanceOf(IdempotencyException.class)
                .satisfies(e -> assertThat(((IdempotencyException) e).code())
                        .isEqualTo("IDEMPOTENT_REQUEST_PROCESSING"));
        assertThat(calls.get()).isZero();
    }

    @Test
    @DisplayName("처리권이 아직 살아 있으면: 넘겨받기를 시도조차 하지 않고 409(#369)")
    void liveLeaseIsNotTakenOver() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.of(IdempotencyRecord.start(KEY, PATH, "POST", hashOf(REQUEST))));

        assertThatThrownBy(() -> service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls)))
                .isInstanceOf(IdempotencyException.class);
        assertThat(calls.get()).isZero();
        verify(repository, never()).takeOverExpiredLease(any(), anyLong(), any(), any(), any());
    }

    @Test
    @DisplayName("처리권을 잃은 뒤 작업이 끝나면: 결과는 돌려주되 저장도 삭제도 하지 않는다(#369)")
    void lostLeaseDoesNotOverwrite() {
        AtomicInteger calls = new AtomicInteger();
        when(repository.findByIdempotencyKeyAndApiPathAndHttpMethod(KEY, PATH, "POST"))
                .thenReturn(Optional.empty());
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(repository.save(any())).thenThrow(
                new ObjectOptimisticLockingFailureException(IdempotencyRecord.class, 1L));

        CheckoutResult result = service.execute(KEY, PATH, "POST", REQUEST, CheckoutResult.class,
                actionReturning(cached(), calls));

        assertThat(result.paymentStatus()).isEqualTo(PaymentStatus.DONE);
        verify(repository, never()).delete(any(IdempotencyRecord.class));
        assertThat(meterRegistry.counter("idempotency.lease.lost").count()).isEqualTo(1.0);
    }
}
