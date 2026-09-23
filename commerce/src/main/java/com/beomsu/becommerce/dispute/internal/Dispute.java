package com.beomsu.becommerce.dispute.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 분쟁/차지백 애그리거트.
 *
 * <p>상태 전이는 엔티티 메서드({@link #submitEvidence}, {@link #resolve})로만 일어나며 허용되지 않은
 * 전이는 {@link DisputeException#invalidTransition}으로 막는다: OPEN에서만 증빙 제출, OPEN/증빙제출
 * 상태에서만 승패 확정. {@code chargebackId}는 외부 차지백 식별자이자 <b>멱등키</b>(unique)로, 같은
 * 차지백에 대한 중복 웹훅이 두 번째 분쟁을 만들지 못하게 DB가 차단한다.
 */
@Entity
@Table(name = "disputes",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_dispute_chargeback", columnNames = "chargebackId"))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Dispute {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 낙관적 락 — 동시 승패 확정(WON/LOST) 레이스를 막는다. 두 어드민 요청이 같은 OPEN 분쟁을 읽어
     * 각각 WON/LOST로 커밋하려 하면, 나중 커밋이 버전 충돌로 실패한다. 이게 없으면 최종 상태는 WON인데
     * 이미 발행된 LOST 이벤트로 승소 분쟁에 역분개가 찍힐 수 있다.
     */
    @Version
    private long version;

    /** 외부(PG/카드사) 차지백 식별자 — 멱등키. */
    @Column(nullable = false, length = 200)
    private String chargebackId;

    @Column(nullable = false, length = 200)
    private String orderNo;

    /** 원결제 식별자. 웹훅이 안 실어줄 수도 있어 nullable. */
    @Column
    private Long paymentId;

    /** 분쟁 금액(원). */
    @Column(nullable = false)
    private long amount;

    /** 차지백 사유(카드사 코드/설명). */
    @Column(length = 500)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private DisputeStatus status;

    /** 대응(증빙 제출) 기한 — 이 시점까지 다투지 못하면 사실상 패소 처리된다. */
    @Column(nullable = false)
    private Instant respondByDeadline;

    /** 제출한 증빙 요약(메모). 증빙 제출 시 채워진다. */
    @Column(length = 1000)
    private String evidenceMemo;

    @Column(nullable = false)
    private Instant createdAt;

    /** 승패 확정 시각(nullable — 미해결이면 null). */
    @Column
    private Instant resolvedAt;

    private Dispute(String chargebackId, String orderNo, Long paymentId, long amount,
                    String reason, Instant respondByDeadline) {
        this.chargebackId = chargebackId;
        this.orderNo = orderNo;
        this.paymentId = paymentId;
        this.amount = amount;
        this.reason = reason;
        this.status = DisputeStatus.OPEN;
        this.respondByDeadline = respondByDeadline;
        this.createdAt = Instant.now();
    }

    /** 차지백 수신으로 분쟁을 개시한다 — OPEN 상태로 생성. 금액은 양수여야 한다(0/음수는 원장 역분개를 깨뜨림). */
    public static Dispute open(String chargebackId, String orderNo, Long paymentId, long amount,
                               String reason, Instant respondByDeadline) {
        if (amount <= 0) {
            throw new DisputeException("INVALID_DISPUTE_AMOUNT", "분쟁 금액은 양수여야 합니다: " + amount);
        }
        return new Dispute(chargebackId, orderNo, paymentId, amount, reason, respondByDeadline);
    }

    /** OPEN → EVIDENCE_SUBMITTED. 증빙 메모를 남긴다. OPEN이 아니면 전이 예외. */
    public void submitEvidence(String memo) {
        if (status != DisputeStatus.OPEN) {
            throw DisputeException.invalidTransition(status, DisputeStatus.EVIDENCE_SUBMITTED);
        }
        this.evidenceMemo = memo;
        this.status = DisputeStatus.EVIDENCE_SUBMITTED;
    }

    /**
     * 승패 확정. OPEN/EVIDENCE_SUBMITTED → WON/LOST, resolvedAt을 찍는다. 이미 최종 상태면 전이 예외.
     *
     * @param win true=승소(WON), false=패소(LOST)
     */
    public void resolve(boolean win) {
        if (status != DisputeStatus.OPEN && status != DisputeStatus.EVIDENCE_SUBMITTED) {
            throw DisputeException.invalidTransition(status, win ? DisputeStatus.WON : DisputeStatus.LOST);
        }
        this.status = win ? DisputeStatus.WON : DisputeStatus.LOST;
        this.resolvedAt = Instant.now();
    }
}
