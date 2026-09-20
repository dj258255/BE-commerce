package com.beomsu.becommerce.subscription.dunning;

import com.beomsu.becommerce.subscription.billing.BillingResult;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * dunning(회수) 시도 이력 — append-only.
 *
 * <p>구독별 청구 시도의 결과를 남긴다. soft decline은 {@link #nextRetryAt}에 다음 재시도 예정일을
 * 기록하고, hard decline·성공·재시도 소진은 {@code nextRetryAt = null}(더 이상 예약된 재시도 없음)이다.
 * 이 이력의 soft decline 개수가 재시도 소진 판정과 attemptNo의 근거가 된다.
 */
@Entity
@Table(name = "dunning_attempts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DunningAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long subscriptionId;

    /** 몇 번째 청구 시도인지(1부터). */
    @Column(nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingResult result;

    /** 다음 재시도 예정일. 예약된 재시도가 없으면 null(성공·hard decline·재시도 소진). */
    @Column
    private LocalDate nextRetryAt;

    @Column(nullable = false)
    private Instant createdAt;

    private DunningAttempt(Long subscriptionId, int attemptNo, BillingResult result, LocalDate nextRetryAt) {
        this.subscriptionId = subscriptionId;
        this.attemptNo = attemptNo;
        this.result = result;
        this.nextRetryAt = nextRetryAt;
        this.createdAt = Instant.now();
    }

    public static DunningAttempt of(Long subscriptionId, int attemptNo, BillingResult result, LocalDate nextRetryAt) {
        return new DunningAttempt(subscriptionId, attemptNo, result, nextRetryAt);
    }
}
