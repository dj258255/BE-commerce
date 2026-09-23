package com.beomsu.becommerce.settlement.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 이미 정산된 뒤에 온 취소를 <b>차기 정산에서 회수</b>하기 위한 조정 항목.
 *
 * <p><b>왜 카운터로는 부족한가</b>: 예전에는 {@code settlement.postsettle.cancel} 카운터만 올렸다.
 * 그러면 "몇 건 있었다"는 알지만 <b>어떤 주문을 얼마 조정해야 하는지</b>는 복구할 수 없다.
 * 프로세스가 재시작되면 운영이 처리할 목록조차 남지 않는다. 원장은 취소 <b>이력</b>을 갖고 있지만,
 * 그건 근거지 <b>실행할 일</b>이 아니다.
 *
 * <p><b>왜 원 정산을 고치지 않는가</b>: 그 정산은 이미 지급 대상으로 나갔다. 과거 집계를 수정하면
 * 이미 산출된 지급금과 어긋나고, 그때 무엇을 근거로 얼마를 줬는지 추적할 수 없게 된다.
 * 회계에서 지워진 기록은 기록이 아니다. 그래서 <b>과거를 고치지 않고 차기에 음수로 반영</b>한다.
 * 원장이 취소를 역분개로 쌓는 것과 같은 형태다.
 *
 * <p><b>멱등</b>: {@code (order_no, cancel_seq)} 유니크. 같은 취소가 재배달돼도 조정이 두 번 생기지 않는다.
 */
@Entity
@Table(name = "settlement_adjustments",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_adjustment_order_seq",
                columnNames = {"order_no", "cancel_seq"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SettlementAdjustment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(nullable = false)
    private Long paymentId;

    /** 회수 대상이 나간 원 정산. 어느 지급에서 잘못 나갔는지를 남긴다. */
    @Column(nullable = false)
    private Long originalSettlementId;

    /** 결제 도메인이 부여한 취소 순번. 멱등 키의 절반이다. */
    @Column(nullable = false)
    private int cancelSeq;

    /** 회수할 금액. <b>음수로 저장한다</b> — 차기 정산에서 그대로 더하면 된다. */
    @Column(nullable = false)
    private long adjustmentAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementAdjustmentStatus status;

    /** 실제로 반영된 정산. 반영 전에는 null. */
    @Column
    private Long appliedSettlementId;

    @Column(nullable = false)
    private Instant createdAt;

    @Column
    private Instant appliedAt;

    private SettlementAdjustment(String orderNo, Long paymentId, Long originalSettlementId,
                                 int cancelSeq, long adjustmentAmount) {
        this.orderNo = orderNo;
        this.paymentId = paymentId;
        this.originalSettlementId = originalSettlementId;
        this.cancelSeq = cancelSeq;
        this.adjustmentAmount = adjustmentAmount;
        this.status = SettlementAdjustmentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    /**
     * 정산된 뒤 취소가 왔을 때 만든다.
     *
     * @param recoverable 회수할 금액(양수로 받아 음수로 저장한다)
     */
    static SettlementAdjustment pendingClawback(String orderNo, Long paymentId,
                                                Long originalSettlementId, int cancelSeq,
                                                long recoverable) {
        if (recoverable <= 0) {
            throw new IllegalArgumentException("회수 금액은 양수여야 합니다: " + recoverable);
        }
        return new SettlementAdjustment(orderNo, paymentId, originalSettlementId,
                cancelSeq, -recoverable);
    }

    /** 차기 정산에 반영됐다. */
    void applyTo(Long settlementId, LocalDate ignoredSettlementDate) {
        if (status != SettlementAdjustmentStatus.PENDING) {
            return;   // 멱등
        }
        this.status = SettlementAdjustmentStatus.APPLIED;
        this.appliedSettlementId = settlementId;
        this.appliedAt = Instant.now();
    }

    /**
     * 자동으로 반영할 수 없다 — 사람이 봐야 한다.
     *
     * <p>차기 정산의 총액이 회수액보다 작으면 음수 지급이 된다. 그건 "돈을 돌려받는" 일이라
     * 지급 파이프라인이 아니라 별도 청구 절차다. 조용히 0으로 깎지 않는다.
     */
    void requireReview() {
        if (status == SettlementAdjustmentStatus.PENDING) {
            this.status = SettlementAdjustmentStatus.REVIEW_REQUIRED;
        }
    }
}
