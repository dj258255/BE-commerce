package com.beomsu.becommerce.live;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 방송 하나의 "지금 고정된 상품" 상태(R8) — 방송당 행이 하나다({@code uk_live_pins_broadcast}).
 * 동시에 고정된 상품이 항상 1개라는 요구(R8 인수 조건)를 "행이 하나뿐"이라는 사실로 강제한다 —
 * 별도 "이전 고정 해제" 호출이 필요 없다. {@link #pin}이 그 자리를 그냥 덮어쓴다.
 *
 * <p>{@code seq}는 이 방송의 고정 이벤트(고정·해제·가격 변경)마다 단조 증가한다(R9) — 시청자
 * 클라이언트가 "이 이벤트보다 먼저 받은 게 있는가"를 판정하는 유일한 기준이다. {@code
 * effectiveAt}은 그 이벤트가 커밋된 서버 시각이고, 클라이언트는 재생 시점이 거기 도달한
 * 뒤에만 화면을 바꾼다(R9.1) — 엔티티는 그 동기화를 모른다, 그냥 "언제 바뀌었는가"만 남긴다.
 *
 * <p>수량 선점·주문 확정(R10~R15)은 이 모듈 범위가 아니다 — {@code limitedQuantity}는 판매자가
 * 건 한정 수량 그대로이고, 아직 아무것도 빼지 않는다({@code LivePinEventView}의
 * {@code remainingQuantity}도 지금은 항상 이 값과 같다).
 */
@Entity
@Table(name = "live_pins", uniqueConstraints = @UniqueConstraint(name = "uk_live_pins_broadcast", columnNames = "broadcastId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class LivePin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long broadcastId;

    /** null이면 지금 고정된 상품이 없다({@link #isPinned}). */
    private Long productId;

    /** 방송 특가(원). */
    private Long price;

    /** 판매자가 건 한정 수량 — 선점이 없는 지금 단계에서는 "남은 수량"과 같다. */
    private Integer limitedQuantity;

    @Column(nullable = false)
    private long seq;

    /** 가장 최근 이벤트(고정·해제·가격 변경)가 커밋된 서버 시각. 아직 아무 일도 없었으면 null. */
    private Instant effectiveAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private LivePin(long broadcastId, Instant now) {
        this.broadcastId = broadcastId;
        this.seq = 0;
        this.updatedAt = now;
    }

    /** 아직 아무것도 고정된 적 없는 방송의 빈 상태로 시작한다(R9.2 스냅샷의 기본값). */
    public static LivePin forBroadcast(long broadcastId, Instant now) {
        return new LivePin(broadcastId, now);
    }

    /**
     * 고정(R8.1) — 이미 다른 상품이 고정돼 있어도 그냥 덮어쓴다(R8.2: "이전 고정은 자동
     * 해제"). seq를 올리고 effectiveAt을 지금으로 찍는다.
     */
    public LivePinEventType pin(long productId, long price, int limitedQuantity, Instant now) {
        requirePositivePrice(price);
        requirePositiveQuantity(limitedQuantity);
        this.productId = productId;
        this.price = price;
        this.limitedQuantity = limitedQuantity;
        this.seq++;
        this.effectiveAt = now;
        this.updatedAt = now;
        return LivePinEventType.PINNED;
    }

    /**
     * 해제 — 멱등이다({@code ShortVideo.unlinkProduct}와 같은 관례). 이미 고정된 상품이
     * 없으면 아무 일도 하지 않고 {@code false}를 돌려준다(seq도 그대로 — 바뀐 게 없으니
     * 이벤트를 새로 낼 이유가 없다).
     */
    public boolean unpin(Instant now) {
        if (!isPinned()) {
            return false;
        }
        this.productId = null;
        this.price = null;
        this.limitedQuantity = null;
        this.seq++;
        this.effectiveAt = now;
        this.updatedAt = now;
        return true;
    }

    /** 고정된 상품의 특가만 바꾼다(R9.1) — 지금 고정된 상품이 없으면 거절한다. */
    public void changePrice(long newPrice, Instant now) {
        if (!isPinned()) {
            throw LivePinException.nothingPinned(broadcastId);
        }
        requirePositivePrice(newPrice);
        this.price = newPrice;
        this.seq++;
        this.effectiveAt = now;
        this.updatedAt = now;
    }

    public boolean isPinned() {
        return productId != null;
    }

    private static void requirePositivePrice(long price) {
        if (price <= 0) {
            throw LivePinException.invalidPrice(price);
        }
    }

    private static void requirePositiveQuantity(int quantity) {
        if (quantity <= 0) {
            throw LivePinException.invalidQuantity(quantity);
        }
    }
}
