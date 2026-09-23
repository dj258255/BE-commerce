package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 결제 상태 전이 이력 — append-only.
 *
 * <p>절대 UPDATE/DELETE 하지 않는다(운영에서는 DB 권한으로도 막는다). 감사 추적과 대사,
 * CS 타임라인의 근거가 된다. 전자금융거래법상 거래기록 보존의 기반이기도 하다.
 */
@Entity
@Table(name = "payment_history")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
// 같은 모듈의 다른 패키지가 참조하므로 public 이다. 모듈 밖 접근은 ModularityTests 가 막는다.
public class PaymentHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TriggeredBy triggeredBy;

    @Column(length = 500)
    private String reason;

    @Column(nullable = false)
    private Instant createdAt;

    private PaymentHistory(Payment payment, PaymentStatus fromStatus, PaymentStatus toStatus,
                           TriggeredBy triggeredBy, String reason) {
        this.payment = payment;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.triggeredBy = triggeredBy;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    static PaymentHistory of(Payment payment, PaymentStatus from, PaymentStatus to,
                             TriggeredBy by, String reason) {
        return new PaymentHistory(payment, from, to, by, reason);
    }
}
