package com.beomsu.becommerce.audit;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** 감사 로그 한 줄 — append-only. UPDATE/DELETE 하지 않는다(전금법 보존). */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String actor;      // 누가 (사용자/어드민 ID)

    @Column(nullable = false, length = 60)
    private String action;     // 무엇을 (FORCE_CANCEL, UNMASK_PII 등)

    @Column(nullable = false, length = 40)
    private String targetType; // 대상 유형 (PAYMENT, ORDER ...)

    @Column(nullable = false, length = 64)
    private String targetId;

    @Column(length = 1000)
    private String detail;     // 사유·부가정보

    @Column(nullable = false)
    private Instant createdAt;

    private AuditLog(String actor, String action, String targetType, String targetId, String detail) {
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = Instant.now();
    }

    static AuditLog of(String actor, String action, String targetType, String targetId, String detail) {
        return new AuditLog(actor, action, targetType, targetId, detail);
    }
}
