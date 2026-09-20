package com.beomsu.becommerce.order.internal;

import com.beomsu.becommerce.payment.internal.Payment;
import com.beomsu.becommerce.shared.Money;
import com.beomsu.becommerce.shared.Ulid;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 주문 애그리거트 — 금액 위변조 검증의 기준값인 {@code totalAmount}의 소유자.
 *
 * <p>상태 전이는 {@link #transitionTo}를 통해서만 일어나며, {@link OrderStatus}의 허용 전이표를
 * 위반하면 예외가 난다({@code Payment}와 동일한 가드 방식). 낙관적 락({@code @Version})으로
 * 동시 전이를 감지한다. 결제 금액 검증({@link #verifyAmount})은 total_amount의 소유자인
 * 주문이 직접 담당한다.
 */
@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order {

    /** 유효시간 — PG의 EXPIRED 정책(30분)과 동기화 */
    private static final Duration EXPIRY = Duration.ofMinutes(30);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 가맹점 채번(ULID). PG의 orderId로 그대로 사용하며 외부에 노출되는 식별자다. */
    @Column(nullable = false, length = 64, unique = true)
    private String orderNo;

    @Column(nullable = false)
    private long userId;

    /** 최종 결제 예정 금액 = order_items 소계의 합. 금액 위변조 검증의 기준값. */
    @Column(nullable = false)
    private long totalAmount;

    @Column(nullable = false, length = 3)
    private String currency = "KRW";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private OrderStatus status;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    /** 만료 예정 시각(생성 + 30분). 만료 배치가 이 값으로 EXPIRED 대상을 스캔한다. */
    @Column(nullable = false)
    private Instant expiresAt;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private final List<OrderItem> items = new ArrayList<>();

    private Order(long userId) {
        Instant now = Instant.now();
        this.orderNo = Ulid.generate();
        this.userId = userId;
        this.status = OrderStatus.PENDING_PAYMENT;
        this.createdAt = now;
        this.updatedAt = now;
        this.expiresAt = now.plus(EXPIRY);
    }

    /**
     * 주문 생성 — 항목 소계의 합으로 totalAmount를 계산하고 PENDING_PAYMENT 상태로 만든다.
     * 재고는 여기서 차감하지 않는다(ADR-003: 승인 성공 시 차감).
     */
    public static Order create(long userId, List<OrderItem> items) {
        if (items == null || items.isEmpty()) {
            throw new OrderException("INVALID_REQUEST", "주문 항목이 비어 있습니다.");
        }
        Order order = new Order(userId);
        for (OrderItem item : items) {
            order.addItem(item);
        }
        return order;
    }

    private void addItem(OrderItem item) {
        item.belongTo(this);
        this.items.add(item);
        try {
            this.totalAmount = Math.addExact(this.totalAmount, item.subtotal());
        } catch (ArithmeticException e) {
            throw new OrderException("AMOUNT_OVERFLOW", "주문 금액이 허용 범위를 초과했습니다.");
        }
    }

    /**
     * 금액 위변조 검증 — successUrl로 돌아온 금액과 주문 금액이 일치하는지 확인한다.
     * order가 total_amount의 소유자이므로 검증도 여기서 수행한다.
     */
    public void verifyAmount(Money requested) {
        if (requested.minorUnit() != totalAmount) {
            throw OrderException.amountMismatch(totalAmount, requested.minorUnit());
        }
    }

    /**
     * 소유권 검증 — 요청한 사용자가 이 주문의 주인인지 확인한다. 인증된 principal에서 얻은 userId를
     * 넘겨받아, 남의 주문을 결제·조작(IDOR)하는 것을 막는다. 결제·포인트 차감 <b>이전에</b> 호출한다.
     */
    public void verifyOwner(long userId) {
        if (this.userId != userId) {
            throw OrderException.notOwner(orderNo);
        }
    }

    /** PENDING_PAYMENT → PAYMENT_IN_PROGRESS. 승인 진행 잠금(이중 지불 차단). */
    public void startPayment() {
        transitionTo(OrderStatus.PAYMENT_IN_PROGRESS);
    }

    /** PAYMENT_IN_PROGRESS → PAID. 승인 성공 확정. */
    public void markPaid() {
        transitionTo(OrderStatus.PAID);
    }

    /** PAYMENT_IN_PROGRESS → PENDING_PAYMENT. 승인 명시 실패 시 재시도를 위해 복귀시킨다. */
    public void revertToPending() {
        transitionTo(OrderStatus.PENDING_PAYMENT);
    }

    /** → FAILED. 승인 실패 확정(재시도 불가). */
    public void markFailed() {
        transitionTo(OrderStatus.FAILED);
    }

    /** PENDING_PAYMENT → EXPIRED. 유효시간 경과(만료 배치). */
    public void markExpired() {
        transitionTo(OrderStatus.EXPIRED);
    }

    /** PAID → CANCELED. 결제 취소에 따른 주문 취소. */
    public void cancel() {
        transitionTo(OrderStatus.CANCELED);
    }

    private void transitionTo(OrderStatus target) {
        if (!status.canTransitionTo(target)) {
            throw OrderException.invalidTransition(status, target);
        }
        this.status = target;
        this.updatedAt = Instant.now();
    }

    /**
     * 복구를 시도했다는 사실만 남긴다. <b>상태는 안 바꾼다.</b>
     *
     * <p>복구 배치는 "이 시간 이상 머문 주문"을 <b>updatedAt 으로</b> 고른다. 그래서 시도가
     * 실패해도 이 값을 안 건드리면 그 주문이 <b>다음 회차에도 같은 앞자리를 차지</b>한다.
     * 상한이 100건인데 앞의 100건이 계속 실패하면 101번째는 영영 차례가 안 온다.
     * 실패를 여기 적어 두면 두 가지가 같이 해결된다 — 다음 시도까지 유예가 생기고
     * (다시 임계 시간을 채워야 한다), 정렬에서 뒤로 밀려 다른 건에 차례가 간다.
     */
    public void markRecoveryAttempted() {
        this.updatedAt = Instant.now();
    }

    public Money totalAsMoney() {
        return Money.krw(totalAmount);
    }

    public List<OrderItem> getItems() {
        return List.copyOf(items);
    }
}
