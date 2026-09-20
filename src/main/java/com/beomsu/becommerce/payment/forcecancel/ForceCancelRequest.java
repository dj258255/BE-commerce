package com.beomsu.becommerce.payment.forcecancel;

import com.beomsu.becommerce.payment.PaymentException;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 강제취소 요청 — 운영이 결제를 강제로 취소할 때 거치는 maker-checker(2인 승인) 결재 레코드.
 *
 * <p>한 사람이 단독으로 남의 결제를 취소하지 못하도록, 요청자(maker)와 승인자(checker)를 강제로
 * 분리한다. {@link #approve(String)}가 승인자와 요청자가 같으면 {@code MAKER_CHECKER_VIOLATION}으로
 * 거절하는 것이 이 분리를 강제하는 핵심 가드다. 승인이 통과해야 비로소 실제 결제 취소가 실행된다.
 * 요청·승인·거부는 모두 감사 로그로도 남는다(컨트롤러에서 principal 기록).
 */
@Entity
@Table(name = "force_cancel_requests")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ForceCancelRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long paymentId;

    /** 취소 요청 금액(전액/부분). */
    @Column(nullable = false)
    private long cancelAmount;

    @Column(length = 300)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ForceCancelStatus status;

    /** 요청자(maker) — principal명. */
    @Column(nullable = false, length = 100)
    private String requestedBy;

    /** 승인/거부자(checker) — principal명. 미해결이면 null. */
    @Column(length = 100)
    private String approvedBy;

    @Column(nullable = false)
    private Instant requestedAt;

    /** 승인/거부로 해결된 시각. 미해결이면 null. */
    private Instant resolvedAt;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    private ForceCancelRequest(long paymentId, long cancelAmount, String reason, String requester) {
        Instant now = Instant.now();
        this.paymentId = paymentId;
        this.cancelAmount = cancelAmount;
        this.reason = reason;
        this.requestedBy = requester;
        this.status = ForceCancelStatus.REQUESTED;
        this.requestedAt = now;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** 강제취소 요청 생성 — REQUESTED 상태. 아직 아무것도 취소되지 않는다(승인 대기). */
    public static ForceCancelRequest request(long paymentId, long cancelAmount, String reason, String requester) {
        return new ForceCancelRequest(paymentId, cancelAmount, reason, requester);
    }

    /**
     * 승인(checker) — REQUESTED → EXECUTED.
     *
     * <p>가드 두 겹: ① 이미 처리된 요청이면 상태 전이 거절, ② <b>승인자가 요청자와 같으면
     * {@code MAKER_CHECKER_VIOLATION}</b>으로 거절해 요청자≠승인자를 강제한다. 실제 결제 취소는
     * 이 메서드가 통과한 뒤 서비스가 실행한다.
     */
    public void approve(String approver) {
        requireRequested();
        if (approver != null && approver.equals(requestedBy)) {
            throw new PaymentException("MAKER_CHECKER_VIOLATION",
                    "요청자는 자신의 강제취소를 승인할 수 없습니다.");
        }
        this.status = ForceCancelStatus.EXECUTED;
        this.approvedBy = approver;
        Instant now = Instant.now();
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    /** 거부(checker) — REQUESTED → REJECTED. 상태 가드만 둔다(거부는 실행이 없으므로). */
    public void reject(String approver) {
        requireRequested();
        this.status = ForceCancelStatus.REJECTED;
        this.approvedBy = approver;
        Instant now = Instant.now();
        this.resolvedAt = now;
        this.updatedAt = now;
    }

    private void requireRequested() {
        if (this.status != ForceCancelStatus.REQUESTED) {
            throw new PaymentException("INVALID_STATE_TRANSITION",
                    "이미 처리된 강제취소 요청입니다: " + this.status);
        }
    }
}
