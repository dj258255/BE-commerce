package com.beomsu.becommerce.shorts;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 숏폼 영상 — 업로드부터 변환까지의 상태를 갖는 애그리거트(R22).
 *
 * <p>업로드 직후에는 재생 가능한 실체가 없다. {@link ShortVideoStatus}가 UPLOADING →
 * UPLOADED → PROBING → TRANSCODING → READY로 흐르고, 실패는 {@link #fail}이 FAILED로 기록한다.
 * 재시도는 {@link #startProbing()}으로 PROBING에 다시 들어가며, {@code maxRetries}(3회)를
 * 소진한 실패 호출은 FAILED를 거쳐 즉시 QUARANTINED로 넘어가 더는 자동 재시도 대상이 되지 않는다
 * — 격리된 영상은 운영이 원인을 보고 다시 넣어야 한다(재무장은 이번 단계 범위 밖).
 *
 * <p>허용되지 않은 전이는 {@link ShortVideoStatus#canTransitionTo}가 막고
 * {@link ShortsException#invalidTransition}을 던진다({@code OrderStatus}와 동일한 가드 방식).
 */
@Entity
@Table(name = "short_videos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShortVideo {

    private static final int MAX_RETRIES = 3;
    private static final int FAILURE_REASON_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 업로드한 판매자. 소유권 검증은 업로드 API 단계(다음 작업)에서 쓴다. */
    @Column(nullable = false)
    private long sellerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShortVideoStatus status;

    @Column(length = 500)
    private String failureReason;

    @Column(nullable = false)
    private int retryCount;

    @Column(nullable = false)
    private int maxRetries;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private ShortVideo(long sellerId) {
        Instant now = Instant.now();
        this.sellerId = sellerId;
        this.status = ShortVideoStatus.UPLOADING;
        this.retryCount = 0;
        this.maxRetries = MAX_RETRIES;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 업로드 시작 — UPLOADING 상태로 생성한다. */
    public static ShortVideo upload(long sellerId) {
        return new ShortVideo(sellerId);
    }

    /** 업로드 완료 알림 — UPLOADING → UPLOADED. */
    public void markUploaded() {
        changeStatus(ShortVideoStatus.UPLOADED);
    }

    /** 메타 점검(probe) 시작 — UPLOADED(최초) 또는 FAILED(재시도)에서 들어온다. */
    public void startProbing() {
        changeStatus(ShortVideoStatus.PROBING);
    }

    /** 변환(transcode) 시작 — PROBING에서만. */
    public void startTranscoding() {
        changeStatus(ShortVideoStatus.TRANSCODING);
    }

    /** 변환 완료 — TRANSCODING → READY. */
    public void markReady() {
        changeStatus(ShortVideoStatus.READY);
    }

    /**
     * 실패 기록 — retryCount를 올리고 FAILED로 전이한다. maxRetries(3)에 도달하면 바로 이어서
     * QUARANTINED로 격리해 더는 자동 재시도 대상이 되지 않게 한다.
     */
    public void fail(String reason) {
        this.retryCount++;
        this.failureReason = truncate(reason);
        changeStatus(ShortVideoStatus.FAILED);
        if (this.retryCount >= this.maxRetries) {
            changeStatus(ShortVideoStatus.QUARANTINED);
        }
    }

    /** 재시도를 소진해 격리된 상태인지. */
    public boolean isQuarantined() {
        return this.status == ShortVideoStatus.QUARANTINED;
    }

    private void changeStatus(ShortVideoStatus target) {
        if (!this.status.canTransitionTo(target)) {
            throw ShortsException.invalidTransition(this.status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    private static String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() > FAILURE_REASON_MAX ? reason.substring(0, FAILURE_REASON_MAX) : reason;
    }
}
