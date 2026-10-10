package com.beomsu.becommerce.live;

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

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 라이브 방송 — 스트림 키 발급부터 송출 상태 전이까지를 갖는 애그리거트(R1·R2·R3).
 *
 * <p>{@link LiveBroadcastStatus}가 SCHEDULED → LIVE → ENDED로 흐른다. 허용되지 않은 전이는
 * {@link #changeStatus}가 막고 {@link LiveBroadcastException#invalidTransition}을 던진다
 * ({@code ShortVideoStatus}·{@code OrderStatus}와 동일한 가드 방식).
 *
 * <p>끊김 재접속 유예(R3)는 상태를 바꾸지 않는다 — {@link #recordDisconnect}는
 * {@code disconnectedAt}만 기록하고(LIVE를 유지), {@link #startOrResumePublish}가 유예 안에
 * 다시 붙으면 그 값을 지운다. 유예를 넘기면 {@link LiveBroadcastGraceScheduler}가
 * {@link #endFromGraceTimeout}으로 끝맺는다 — 엔티티 자신은 "지금이 유예를 넘겼는지" 시계를
 * 들고 판단하지 않는다({@link #isGraceExpired}가 호출자가 준 시각으로만 판단한다, 테스트
 * 용이성).
 *
 * <p>주문·결제·재고 로직은 이 애그리거트에 없다(R32 제약) — 방송 중 상품 고정·주문은 다음
 * 단계이고, 그때도 commerce의 공개 API만 부른다.
 */
@Entity
@Table(name = "live_broadcasts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LiveBroadcast {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 방송을 만든 판매자. 소유권 검증(IDOR 방지)은 {@code LiveBroadcastService}가 이 값으로 한다. */
    @Column(nullable = false)
    private long sellerId;

    /** 방송마다 하나, 유일(R1) — MediaMTX 송출 인증(R2)이 이 값으로 방송을 찾는다. */
    @Column(nullable = false, unique = true, length = 32)
    private String streamKey;

    /** 방송 제목(R1.1 — 생성 요청에 실어 보낸다, 응답에도 그대로 담는다). */
    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private LiveBroadcastStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** 처음 LIVE가 된 시각. SCHEDULED인 동안은 null. */
    private Instant startedAt;

    /** ENDED가 된 시각. 그 전까지는 null. */
    private Instant endedAt;

    /** 송출이 끊긴 시각(LIVE 유지 중의 재접속 유예 추적용) — 끊긴 적 없거나 이미 재접속했으면 null. */
    private Instant disconnectedAt;

    private LiveBroadcast(long sellerId, String streamKey, String title, Instant now) {
        this.sellerId = sellerId;
        this.streamKey = streamKey;
        this.title = title;
        this.status = LiveBroadcastStatus.SCHEDULED;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * 방송 생성(R1.1) — SCHEDULED로 시작하고 스트림 키를 발급한다. {@code title}이 비어 있으면
     * {@link LiveBroadcastException#invalidTitle}(400)로 거절한다.
     */
    public static LiveBroadcast schedule(long sellerId, String streamKey, String title, Instant now) {
        Objects.requireNonNull(streamKey, "streamKey는 필수입니다");
        Objects.requireNonNull(now, "now는 필수입니다");
        if (title == null || title.isBlank()) {
            throw LiveBroadcastException.invalidTitle();
        }
        return new LiveBroadcast(sellerId, streamKey, title, now);
    }

    /** MediaMTX 송출 인증(R2)이 묻는 것 — 이 방송이 지금 송출을 받아도 되는 상태인지. */
    public boolean canAcceptPublish() {
        return status == LiveBroadcastStatus.SCHEDULED || status == LiveBroadcastStatus.LIVE;
    }

    /**
     * 송출 시작 훅(R3) — SCHEDULED에서 처음 붙으면 LIVE로 전이하고 {@code true}를 돌려준다
     * (호출자가 live.started를 발행해야 한다는 신호). 이미 LIVE인데 재접속(유예 안에 다시
     * 붙음)이면 {@code disconnectedAt}만 지우고 {@code false}(새 이벤트 없음)를 돌려준다.
     */
    public boolean startOrResumePublish(Instant now) {
        if (status == LiveBroadcastStatus.SCHEDULED) {
            changeStatus(LiveBroadcastStatus.LIVE, now);
            this.startedAt = now;
            this.disconnectedAt = null;
            return true;
        }
        if (status == LiveBroadcastStatus.LIVE) {
            this.disconnectedAt = null; // 재접속 — 유예 해제
            this.updatedAt = now;
            return false;
        }
        throw LiveBroadcastException.invalidTransition(status, LiveBroadcastStatus.LIVE);
    }

    /**
     * 송출 끊김 훅(R3) — LIVE 상태일 때만 {@code disconnectedAt}을 기록한다(상태는 그대로
     * LIVE, 재접속 유예 시작). LIVE가 아닌 상태에서 끊김 훅이 와도(이미 ENDED 등) 조용히
     * 무시한다 — MediaMTX 훅은 네트워크 사정으로 중복·지연 호출될 수 있어, 여기서 예외를
     * 던지면 그 흔한 경우로 로그만 시끄러워진다.
     */
    public void recordDisconnect(Instant now) {
        if (status != LiveBroadcastStatus.LIVE) {
            return;
        }
        this.disconnectedAt = now;
        this.updatedAt = now;
    }

    /** 재접속 유예(기본 30초)를 넘겼는지 — 호출자가 "지금"과 유예 길이를 준다(시계 주입, 테스트 용이성). */
    public boolean isGraceExpired(Instant now, Duration grace) {
        return status == LiveBroadcastStatus.LIVE
                && disconnectedAt != null
                && disconnectedAt.plus(grace).isBefore(now);
    }

    /** 재접속 유예를 넘겨 끝맺는다(R3) — {@link LiveBroadcastGraceScheduler}만 부른다. */
    public void endFromGraceTimeout(Instant now) {
        changeStatus(LiveBroadcastStatus.ENDED, now);
        this.endedAt = now;
        this.disconnectedAt = null;
    }

    private void changeStatus(LiveBroadcastStatus target, Instant now) {
        if (!canTransition(status, target)) {
            throw LiveBroadcastException.invalidTransition(status, target);
        }
        this.status = target;
        this.updatedAt = now;
    }

    private static boolean canTransition(LiveBroadcastStatus from, LiveBroadcastStatus to) {
        return switch (from) {
            case SCHEDULED -> to == LiveBroadcastStatus.LIVE;
            case LIVE -> to == LiveBroadcastStatus.ENDED;
            case ENDED -> false;
        };
    }
}
