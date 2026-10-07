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
import java.util.Objects;

/**
 * 숏폼 영상 — 업로드부터 변환까지의 상태를 갖는 애그리거트(R21·R22).
 *
 * <p>업로드 직후에는 재생 가능한 실체가 없다. {@link ShortVideoStatus}가 UPLOADING →
 * UPLOADED → PROBING → TRANSCODING → READY로 흐르고, 실패는 {@link #fail}이 FAILED로 기록한다.
 * 재시도는 {@link #startProbing()}으로 PROBING에 다시 들어가며, {@code maxRetries}(3회)를
 * 소진한 실패 호출은 FAILED를 거쳐 즉시 QUARANTINED로 넘어가 더는 자동 재시도 대상이 되지 않는다
 * — 격리된 영상은 운영이 원인을 보고 다시 넣어야 한다(재무장은 이번 단계 범위 밖).
 *
 * <p>허용되지 않은 전이는 {@link ShortVideoStatus#canTransitionTo}가 막고
 * {@link ShortsException#invalidTransition}을 던진다({@code OrderStatus}와 동일한 가드 방식).
 *
 * <p>{@code objectKey}·{@link UploadMeta}는 업로드 시작 시 확정된다(R21) — 저장소에 presigned
 * URL을 내준 바로 그 키이고, 길이·크기·세로 비율은 {@link UploadMeta}의 컴팩트 생성자가 생성 시점에
 * 이미 검증했다. 실제 파일 내용 검증(코덱·정확한 해상도 등)은 PROBING 단계(변환 워커)의 몫이다.
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

    /** 업로드한 판매자. 소유권 검증(IDOR 방지)은 {@code ShortsService}가 이 값으로 한다. */
    @Column(nullable = false)
    private long sellerId;

    /** 저장소 객체 키 — presigned 업로드 URL이 가리키는 바로 그 대상. 조회용으로만 쓴다. */
    @Column(nullable = false, unique = true, length = 300)
    private String objectKey;

    @Column(length = 100)
    private String contentType;

    @Column(nullable = false)
    private int durationSeconds;

    @Column(nullable = false)
    private long fileSizeBytes;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

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

    private ShortVideo(long sellerId, String objectKey, UploadMeta meta) {
        Instant now = Instant.now();
        this.sellerId = sellerId;
        this.objectKey = objectKey;
        this.contentType = meta.contentType();
        this.durationSeconds = meta.durationSeconds();
        this.fileSizeBytes = meta.fileSizeBytes();
        this.width = meta.width();
        this.height = meta.height();
        this.status = ShortVideoStatus.UPLOADING;
        this.retryCount = 0;
        this.maxRetries = MAX_RETRIES;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 업로드 시작 — UPLOADING 상태로 생성한다. {@code meta}의 검증(길이·크기·세로 비율, R21)은
     * {@link UploadMeta}의 컴팩트 생성자가 이미 통과시킨 값이어야 한다.
     */
    public static ShortVideo upload(long sellerId, String objectKey, UploadMeta meta) {
        Objects.requireNonNull(objectKey, "objectKey는 필수입니다");
        Objects.requireNonNull(meta, "meta는 필수입니다");
        return new ShortVideo(sellerId, objectKey, meta);
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
