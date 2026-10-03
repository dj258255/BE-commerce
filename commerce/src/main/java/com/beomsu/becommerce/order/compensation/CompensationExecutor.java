package com.beomsu.becommerce.order.compensation;

import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.shared.Money;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * 보상 태스크 1건을 실제로 실행한다 — 태스크마다 별도 트랜잭션.
 *
 * <p>한 태스크의 성공/실패가 각자의 트랜잭션 경계 안에서만 커밋·롤백되게 해, 한 건의 롤백이
 * 다른 태스크나 배치 전체를 오염시키지 않게 한다. 실패 기록({@link #recordFailure})은 시도
 * 트랜잭션과 분리된 별도 트랜잭션으로 남긴다(시도 tx가 롤백돼도 실패 카운트는 보존).
 */
@Service
@RequiredArgsConstructor
public class CompensationExecutor {

    /** 지수 백오프 상한(초) — 재시도 간격이 무한정 벌어지지 않게 한다. */
    private static final long MAX_BACKOFF_SECONDS = 300;

    private final CompensationTaskRepository repository;
    private final PaymentService paymentService;
    private final MeterRegistry meterRegistry;

    /**
     * 태스크 1건 실행. 이미 PENDING이 아니면(멱등) 아무 것도 하지 않는다.
     *
     * <p>망취소 성공 → DONE(SUCCEEDED). <b>이미 취소된 결제</b>(PAYMENT_ALREADY_SETTLED) → 보상 완료로 DONE.
     * <b>승인 실패 확정뿐</b>(PAYMENT_APPROVAL_FAILED) → 취소할 원거래가 없으므로 DONE(사유를 남긴다).
     * <b>결제가 미확정</b>(PAYMENT_UNRESOLVED) → 실패가 아니라 보류다 — PENDING 을 유지하고 retryCount 를
     * 올리지 않은 채 다음 시도만 미룬다. <b>결제가 아예 없음</b>(PAYMENT_NOT_FOUND) → 확정하지 않고
     * 운영자에게 넘긴다. PG 전송 실패는 <b>결과의 확정 여부</b>가 다르므로 나눠 기록한다 —
     * {@code CANCEL_CONNECT_FAILED}(바이트 미전송 → 확정 실패)와 {@code CANCEL_OUTCOME_UNKNOWN}(전송 후
     * 응답 미수신 → 결과 미확정). 판별은 payment 모듈이 PG 계층에서 하고 코드로 번역해 넘긴다(모듈 경계상
     * order 가 payment.pg 를 직접 참조할 수 없다). 그 외 예외는 그대로 던져 롤백시키고 호출자가 별도
     * 트랜잭션으로 실패를 기록한다.
     */
    @Transactional
    public void attempt(Long taskId) {
        CompensationTask task = repository.findById(taskId).orElse(null);
        if (task == null || task.getStatus() != CompensationStatus.PENDING) {
            return; // 없거나 이미 처리됨 — 멱등
        }
        try {
            paymentService.cancelByOrderNo(task.getOrderNo(), Money.krw(task.getAmount()), task.getReason());
            task.markDone();
            meterRegistry.counter("compensation.processed", "outcome", "success").increment();
        } catch (PaymentException e) {
            handlePaymentException(task, e);
        }
    }

    /** 취소 결과의 사유를 구분해 분기한다 — "없다"를 뭉뚱그리면 보상이 조용히 사라진다. */
    private void handlePaymentException(CompensationTask task, PaymentException e) {
        switch (e.code()) {
            case "CANCEL_CONNECT_FAILED" -> {
                // 연결 거부·호스트 미해석 — 요청 바이트가 나가지 않았음이 보장된다. 재시도 대상이되 결과는 확정이다.
                retryable(task, e, CompensationOutcome.FAILED_DEFINITE, "connect_failed");
            }
            case "CANCEL_OUTCOME_UNKNOWN" -> {
                // 전송 뒤 응답을 받지 못했다 — 취소가 실제로 나갔을 수 있어 결과를 모른다. 확정 실패로 적지 않는다.
                retryable(task, e, CompensationOutcome.OUTCOME_UNKNOWN, "outcome_unknown");
            }
            case "CANCEL_NOT_RETRYABLE" -> {
                // PG가 "다시 보내도 같다"고 확정한 경우 — 재시도 예산을 태우지 않고 즉시 운영자에게
                // 넘긴다. 이길 수 없는 요청이 재시도를 다 쓰는 동안 진짜 봐야 할 건이 알림에 묻힌다.
                task.markNotRetryable(e.getMessage());
                meterRegistry.counter("compensation.processed", "outcome", "not_retryable").increment();
                meterRegistry.counter("compensation.exhausted").increment();
            }
            case "PAYMENT_ALREADY_SETTLED" -> {
                // 결제는 있는데 취소 가능 상태가 아니다 = 이미 취소 확정됐다 → 보상 완료.
                task.markDone();
                meterRegistry.counter("compensation.processed", "outcome", "already").increment();
            }
            case "PAYMENT_APPROVAL_FAILED" -> {
                // 승인 실패 확정뿐 — 취소할 원거래 자체가 없다. 완료로 닫되 사유를 남겨 "이미 취소됨"과 구분한다.
                task.markDoneWithReason(e.getMessage());
                meterRegistry.counter("compensation.processed", "outcome", "approval_failed").increment();
            }
            case "PAYMENT_UNRESOLVED" -> {
                // 결제가 아직 미확정 — 실패가 아니라 보류다. retryCount 를 올리지 않고 다음 시도만 미룬다.
                // 복구 배치가 결제를 확정하면 다음 주기에 정상 경로를 탄다.
                task.holdUnresolved(e.getMessage(), backoffInstant(task.getRetryCount()));
                meterRegistry.counter("compensation.processed", "outcome", "skipped_unresolved").increment();
            }
            case "PAYMENT_NOT_FOUND" -> {
                // 결제가 <아예 없다>. 보상 태스크가 있다는 건 승인이 났다는 뜻이므로 앞뒤가 안 맞는다.
                // 잘못된 주문번호일 수도, 전파 지연일 수도 있다. "없다"를 "이미 했다"로 읽으면
                // 그 보상이 조용히 사라진다. 확정하지 않고 운영자에게 넘긴다.
                task.markNotRetryable("취소할 결제를 찾을 수 없음 — 승인 기록과 대조 필요: " + e.getMessage());
                meterRegistry.counter("compensation.processed", "outcome", "not_found").increment();
                meterRegistry.counter("compensation.exhausted").increment();
            }
            default -> throw e; // 그 외 결제 예외는 재시도 대상 — 트랜잭션 롤백
        }
    }

    /** 재시도 대상 실패를 결과 분류와 함께 기록한다(호출 트랜잭션 안에서 — 태스크는 managed). */
    private void retryable(CompensationTask task, Exception e, CompensationOutcome outcome, String metricTag) {
        task.recordFailure(e.getMessage(), backoffInstant(task.getRetryCount()), outcome);
        if (task.isExhausted()) {
            meterRegistry.counter("compensation.exhausted").increment();
        }
        meterRegistry.counter("compensation.processed", "outcome", metricTag).increment();
    }

    /** 시도 횟수에 따른 다음 시도 시각 — 지수 백오프, 상한 {@link #MAX_BACKOFF_SECONDS}초. */
    private static Instant backoffInstant(int retryCount) {
        long backoff = Math.min(MAX_BACKOFF_SECONDS, (long) Math.pow(2, retryCount + 1));
        return Instant.now().plusSeconds(backoff);
    }

    /**
     * 시도 실패를 별도 트랜잭션으로 기록한다. 지수 백오프로 다음 시도 시각을 잡고, 재시도를 소진하면
     * FAILED가 되어 운영 알림 신호(compensation.exhausted)를 남긴다.
     */
    @Transactional
    public void recordFailure(Long taskId, String error) {
        CompensationTask task = repository.findById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        task.recordFailure(error, backoffInstant(task.getRetryCount()));
        if (task.isExhausted()) {
            // 자동 처리를 포기함 — 운영이 개입해야 한다. 알림 룰의 소스가 되는 카운터.
            meterRegistry.counter("compensation.exhausted").increment();
        }
        repository.save(task);
    }
}
