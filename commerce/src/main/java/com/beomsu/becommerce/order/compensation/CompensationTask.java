package com.beomsu.becommerce.order.compensation;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 보상 태스크 — 승인 후 재고 부족 시 카드 망취소처럼, 외부(PG)라서 불확실한 보상을 durable하게 적재한다.
 *
 * <p>체크아웃 트랜잭션이 이 태스크를 승인·재고복원과 <b>같은 트랜잭션</b>으로 커밋하므로, 커밋된 뒤에는
 * 반드시 보상이 시도된다(outbox 성격). 스케줄러가 {@link CompensationStatus#PENDING} 태스크를
 * 지수 백오프로 재시도하고, 재시도를 소진하면 {@link CompensationStatus#FAILED}로 두어 운영이 개입한다.
 */
@Entity
@Table(name = "compensation_tasks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class CompensationTask {

    private static final int LAST_ERROR_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CompensationType type;

    /** 보상 대상 금액(망취소할 카드 승인액). */
    @Column(nullable = false)
    private long amount;

    @Column(length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CompensationStatus status;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private int maxRetries;

    /** 다음 시도 예정 시각. 스케줄러가 이 값이 지난 PENDING 태스크만 집는다. */
    @Column(nullable = false)
    private Instant nextAttemptAt;

    @Column(length = 500)
    private String lastError;

    /** 마지막 시도의 결과 분류 — 취소 성공/확정 실패/결과 미확정/미확정 보류. 기존 행은 null. */
    @Enumerated(EnumType.STRING)
    @Column(length = 30)
    private CompensationOutcome lastOutcome;

    /** 마지막으로 시도한 시각. 시도할 때마다 갱신한다. 기존 행은 null. */
    private Instant lastAttemptAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private CompensationTask(String orderNo, CompensationType type, long amount, String reason) {
        Instant now = Instant.now();
        this.orderNo = orderNo;
        this.type = type;
        this.amount = amount;
        this.reason = reason;
        this.status = CompensationStatus.PENDING;
        this.retryCount = 0;
        this.maxRetries = 5;
        this.nextAttemptAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 카드 망취소 보상 태스크 생성 — PENDING·즉시 시도 가능(nextAttemptAt=now) 상태. */
    public static CompensationTask networkCancel(String orderNo, long amount, String reason) {
        return new CompensationTask(orderNo, CompensationType.NETWORK_CANCEL, amount, reason);
    }

    /** 보상 성공(또는 멱등 완료) 확정. */
    public void markDone() {
        this.status = CompensationStatus.DONE;
        this.lastOutcome = CompensationOutcome.SUCCEEDED;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * 취소할 원거래가 없음이 <b>확정</b>됐지만(승인 실패 확정 등) 보상할 대상이 없어 완료로 닫는다.
     * 사유를 lastError 에 남겨 "이미 취소됨"과 구분한다.
     */
    public void markDoneWithReason(String reason) {
        this.status = CompensationStatus.DONE;
        this.lastError = truncate(reason);
        this.lastOutcome = CompensationOutcome.SUCCEEDED;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * 결제가 아직 미확정이라 이번 시도를 <b>보류</b>한다. 실패가 아니므로 retryCount 를 올리지 않고
     * (재시도 예산을 태우지 않는다), 다음 시도 시각만 뒤로 민다. 복구 배치가 결제를 확정하면 다음
     * 주기에 정상 경로를 탄다.
     */
    public void holdUnresolved(String reason, Instant nextAttempt) {
        this.status = CompensationStatus.PENDING;
        this.nextAttemptAt = nextAttempt;
        this.lastError = truncate(reason);
        this.lastOutcome = CompensationOutcome.SKIPPED_UNRESOLVED;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /**
     * 실패 기록 — retryCount를 올리고 다음 시도 시각을 잡는다. maxRetries에 도달하면 FAILED로 두어
     * 더는 재시도하지 않고(무한 재시도 방지) 운영 개입 신호로 남긴다.
     *
     * <p><b>결과를 단정하지 않는다.</b> 이 경로는 예외를 분류할 수 없는 catch-all(호출자가 예외
     * 종류를 모르는 경우)이 쓴다. 결과를 모르는데 {@code FAILED_DEFINITE}로 적으면 확정 실패와
     * 결과 미확정이 뒤섞인다 — 그래서 {@code lastOutcome} 을 null(미분류)로 둔다.
     */
    public void recordFailure(String error, Instant nextAttempt) {
        recordFailure(error, nextAttempt, null);
    }

    /**
     * 결과 분류를 함께 남기는 실패 기록. 타임아웃처럼 결과를 모르는 실패는 {@code OUTCOME_UNKNOWN} 으로
     * 적어, 확정 실패와 구분한다(둘 다 재시도하지만 재시도의 의미가 다르다).
     */
    public void recordFailure(String error, Instant nextAttempt, CompensationOutcome outcome) {
        this.retryCount++;
        this.lastError = truncate(error);
        this.lastOutcome = outcome;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = Instant.now();
        if (this.retryCount >= this.maxRetries) {
            this.status = CompensationStatus.FAILED;
        } else {
            this.status = CompensationStatus.PENDING;
            this.nextAttemptAt = nextAttempt;
        }
    }

    /** 재시도를 소진해 자동 처리를 포기한 상태인지. */
    /**
     * 재시도해도 결과가 같다고 PG가 확정한 실패 — 남은 예산과 무관하게 즉시 FAILED로 닫는다.
     * 자동 처리를 포기했다는 뜻이라 운영 알림의 대상이 된다.
     */
    public void markNotRetryable(String error) {
        this.retryCount = this.maxRetries;
        this.lastError = error;
        this.status = CompensationStatus.FAILED;
        this.lastOutcome = CompensationOutcome.FAILED_DEFINITE;
        this.lastAttemptAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public boolean isExhausted() {
        return this.status == CompensationStatus.FAILED;
    }

    /**
     * 재무장(reopen) — 근본 원인을 고친 뒤 운영이 소진(FAILED)된 태스크를 다시 시도하게 한다.
     * 상태를 PENDING으로 되돌리고 retryCount를 0으로 리셋해 새 재시도 예산을 주며, 즉시 시도 가능하게
     * nextAttemptAt을 now로 당긴다. lastError는 진단 근거로 남긴다.
     */
    public void reopen() {
        Instant now = Instant.now();
        this.status = CompensationStatus.PENDING;
        this.retryCount = 0;
        this.nextAttemptAt = now;
        this.updatedAt = now;
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > LAST_ERROR_MAX ? error.substring(0, LAST_ERROR_MAX) : error;
    }
}
