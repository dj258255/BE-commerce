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
     * <p>망취소 성공 → DONE. <b>이미 취소된 결제</b>(PAYMENT_ALREADY_SETTLED) → 보상 완료로 DONE.
     * <b>결제가 아예 없음</b>(PAYMENT_NOT_FOUND) → 확정하지 않고 운영자에게 넘긴다 —
     * 보상 태스크가 있다는 건 승인이 났다는 뜻이라 앞뒤가 안 맞고, "없다"를 "이미 했다"로 읽으면
     * 그 보상이 사라진다. 그 외 예외는 그대로 던져 롤백시키고 호출자가 별도 트랜잭션으로 실패를 기록한다.
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
            if ("CANCEL_NOT_RETRYABLE".equals(e.code())) {
                // PG가 "다시 보내도 같다"고 확정한 경우 — 재시도 예산을 태우지 않고 즉시 운영자에게
                // 넘긴다. 이길 수 없는 요청이 재시도를 다 쓰는 동안 진짜 봐야 할 건이 알림에 묻힌다.
                task.markNotRetryable(e.getMessage());
                meterRegistry.counter("compensation.processed", "outcome", "not_retryable").increment();
                meterRegistry.counter("compensation.exhausted").increment();
            } else if ("PAYMENT_ALREADY_SETTLED".equals(e.code())) {
                // 결제는 있는데 취소 가능 상태가 아니다 = 이미 취소됐다 → 보상 완료.
                task.markDone();
                meterRegistry.counter("compensation.processed", "outcome", "already").increment();
            } else if ("PAYMENT_NOT_FOUND".equals(e.code())) {
                // 결제가 <아예 없다>. 보상 태스크가 있다는 건 승인이 났다는 뜻이므로 앞뒤가 안 맞는다.
                // 잘못된 주문번호일 수도, 전파 지연일 수도 있다. "없다"를 "이미 했다"로 읽으면
                // 그 보상이 조용히 사라진다. 확정하지 않고 운영자에게 넘긴다.
                task.markNotRetryable("취소할 결제를 찾을 수 없음 — 승인 기록과 대조 필요: " + e.getMessage());
                meterRegistry.counter("compensation.processed", "outcome", "not_found").increment();
                meterRegistry.counter("compensation.exhausted").increment();
            } else {
                throw e; // 그 외 결제 예외는 재시도 대상 — 트랜잭션 롤백
            }
        }
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
        long backoff = Math.min(MAX_BACKOFF_SECONDS, (long) Math.pow(2, task.getRetryCount() + 1));
        Instant next = Instant.now().plusSeconds(backoff);
        task.recordFailure(error, next);
        if (task.isExhausted()) {
            // 자동 처리를 포기함 — 운영이 개입해야 한다. 알림 룰의 소스가 되는 카운터.
            meterRegistry.counter("compensation.exhausted").increment();
        }
        repository.save(task);
    }
}
