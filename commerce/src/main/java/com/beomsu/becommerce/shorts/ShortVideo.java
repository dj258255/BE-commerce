package com.beomsu.becommerce.shorts;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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
 *
 * <p>상품 연결(R25)은 {@code productIds}에 카탈로그 상품 id만 담는다 — 상품 실존 확인은 엔티티가
 * 카탈로그를 모르므로 {@code ShortsService}가 {@code ProductCatalogFacts}로 먼저 하고, 여기서는
 * 중복 방지(멱등)와 {@link #MAX_LINKED_PRODUCTS} 상한만 지킨다.
 */
@Entity
@Table(name = "short_videos")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ShortVideo {

    private static final int MAX_RETRIES = 3;
    private static final int FAILURE_REASON_MAX = 500;

    /**
     * 영상 하나에 연결할 수 있는 상품 수 상한(R25). 명세는 "2개 이상 연결 가능"만 못박고 정확한
     * 상한은 정하지 않아, 태그 화면이 과도하게 쌓이지 않을 운영 상식선으로 10을 잡는다(가정 —
     * 더 커질 사정이 생기면 이 상수만 바꾸면 된다).
     */
    public static final int MAX_LINKED_PRODUCTS = 10;

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

    /** 연결된 카탈로그 상품 id(R25). 순서는 저장 순서를 보장하지 않으므로 조회는 정렬해서 쓴다. */
    @ElementCollection
    @CollectionTable(name = "short_video_product_links", joinColumns = @JoinColumn(name = "short_video_id"))
    @Column(name = "product_id", nullable = false)
    private Set<Long> productIds = new LinkedHashSet<>();

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

    /**
     * 상품 연결(R25) — 멱등이다(이미 연결된 상품을 다시 연결해도 중복이 생기지 않는다).
     * <b>새 상품</b>을 연결하려는데 이미 {@link #MAX_LINKED_PRODUCTS}개가 연결돼 있으면 거절한다
     * — 이미 연결된 상품을 다시 연결하는 호출은 가득 찬 상태에서도 항상 통과한다(그대로 두는 것과
     * 같은 일이라 거절할 이유가 없다). 상품이 카탈로그에 실존하는지는 호출자({@code ShortsService})가
     * 미리 확인한다 — 엔티티는 카탈로그를 모른다.
     */
    public void linkProduct(long productId) {
        if (!this.productIds.contains(productId) && this.productIds.size() >= MAX_LINKED_PRODUCTS) {
            throw ShortsException.tooManyLinkedProducts(this.id, MAX_LINKED_PRODUCTS);
        }
        this.productIds.add(productId);
        this.updatedAt = Instant.now();
    }

    /** 상품 연결 해제(R25) — 멱등이다. 연결돼 있지 않은 상품을 해제해도 성공(아무 일도 안 일어난다). */
    public void unlinkProduct(long productId) {
        this.productIds.remove(productId);
        this.updatedAt = Instant.now();
    }

    /** 연결된 상품 id — 응답 순서를 결정적으로 하려고 오름차순으로 돌려준다(R25). */
    public List<Long> getLinkedProductIds() {
        return this.productIds.stream().sorted().toList();
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
