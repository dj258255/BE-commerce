package com.beomsu.becommerce.payment.webhook;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 수신한 PG 웹훅 이벤트 원본 — 감사·멱등·재처리의 근거.
 *
 * <p>수신 시점에는 <b>원본 페이로드 저장</b>만 하고 상태 해석은 하지 않는다(페이로드를 신뢰하지 않음).
 * 이후 워커가 조회 API로 실상태를 재검증한 뒤 {@link #markProcessed()} 등으로 결과를 남긴다.
 * {@code externalEventId}에 UNIQUE 제약을 걸어 중복 재전송을 멱등하게 흡수한다.
 */
@Entity
@Table(
        name = "webhook_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_webhook_external_id", columnNames = "externalEventId")
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** PG가 부여한 이벤트 식별자 — 멱등 키(UNIQUE). */
    @Column(nullable = false, length = 100)
    private String externalEventId;

    @Column(length = 60)
    private String eventType;

    /** 원본 JSON 페이로드 전체(감사·재처리용). */
    @Column(columnDefinition = "TEXT")
    private String rawPayload;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private WebhookEventStatus status;

    @Column(nullable = false)
    private Instant receivedAt;

    private Instant processedAt;

    @Column(length = 500)
    private String failReason;

    /** PENDING_PAYMENT 로 미뤄진 횟수. 상한을 넘기면 FAILED 로 넘긴다. */
    @Column(nullable = false)
    private int retryCount;

    /** 다음 재시도 시각. 이 값이 지난 PENDING_PAYMENT 만 스케줄러가 집는다. */
    private Instant nextRetryAt;

    private WebhookEvent(String externalEventId, String eventType, String rawPayload) {
        this.externalEventId = externalEventId;
        this.eventType = eventType;
        this.rawPayload = rawPayload;
        this.status = WebhookEventStatus.RECEIVED;
        this.receivedAt = Instant.now();
    }

    /** 수신 직후 상태(RECEIVED)로 생성한다. */
    public static WebhookEvent received(String externalEventId, String eventType, String rawPayload) {
        return new WebhookEvent(externalEventId, eventType, rawPayload);
    }

    /** 조회 재검증까지 마친 정상 처리 완료. */
    public void markProcessed() {
        this.status = WebhookEventStatus.PROCESSED;
        this.processedAt = Instant.now();
        this.failReason = null;
    }

    /** 처리 실패 — 사유를 남기고 다음 주기 재처리 대상이 된다. */
    public void markFailed(String reason) {
        this.status = WebhookEventStatus.FAILED;
        this.processedAt = Instant.now();
        this.failReason = reason;
    }

    /**
     * 결제 행이 아직 없어 처리를 미룬다. <b>실패가 아니라 순서 문제다.</b>
     *
     * <p>승인 요청은 나갔는데 우리 쪽 결제 행이 커밋되기 전에 PG 웹훅이 먼저 도착하면 이 상태가 된다.
     * 예외로 던지면 아웃박스에 미완료로 남는데, 그 재시도는 재기동 때만 돌아 그때까지 방치된다.
     */
    public void markPendingPayment(Instant nextRetryAt) {
        this.status = WebhookEventStatus.PENDING_PAYMENT;
        this.retryCount += 1;
        this.nextRetryAt = nextRetryAt;
        this.failReason = "결제 행 없음 — 웹훅이 승인 응답보다 먼저 도착";
    }

    /** 처리 대상이 아니라 건너뜀(예: paymentKey 없음). */
    public void markSkipped(String reason) {
        this.status = WebhookEventStatus.SKIPPED;
        this.processedAt = Instant.now();
        this.failReason = reason;
    }
}
