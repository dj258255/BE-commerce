package com.beomsu.becommerce.order.idempotency;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.time.Instant;

/**
 * 멱등키 레코드.
 *
 * <p>중복 판별 기준은 (멱등키 + API 경로 + HTTP 메서드) 조합이다(토스페이먼츠와 동일).
 * INSERT 성공 자체가 "처리권 획득"이라는 원자적 잠금 효과를 낸다 — 별도 분산락이 필요 없다.
 * 첫 응답을 저장해 두었다가, 같은 키의 재요청에 그대로 재반환한다.
 */
@Entity
@Table(
        name = "idempotency_keys",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_idem",
                columnNames = {"idempotencyKey", "apiPath", "httpMethod"})
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class IdempotencyRecord {

    /** 유효기간 — 토스페이먼츠와 동일하게 15일. */
    private static final Duration TTL = Duration.ofDays(15);

    public enum Status { PROCESSING, DONE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 300)
    private String idempotencyKey;

    @Column(nullable = false, length = 200)
    private String apiPath;

    @Column(nullable = false, length = 10)
    private String httpMethod;

    /** 요청 본문 해시(SHA-256). 같은 키 + 다른 본문 = 위험한 재사용(422) 판별용. */
    @Column(nullable = false, length = 64)
    private String requestHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(columnDefinition = "TEXT")
    private String responseBody;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private IdempotencyRecord(String key, String apiPath, String httpMethod, String requestHash) {
        Instant now = Instant.now();
        this.idempotencyKey = key;
        this.apiPath = apiPath;
        this.httpMethod = httpMethod;
        this.requestHash = requestHash;
        this.status = Status.PROCESSING;
        this.createdAt = now;
        this.expiresAt = now.plus(TTL);
    }

    static IdempotencyRecord start(String key, String apiPath, String httpMethod, String requestHash) {
        return new IdempotencyRecord(key, apiPath, httpMethod, requestHash);
    }

    void complete(String responseBody) {
        this.responseBody = responseBody;
        this.status = Status.DONE;
    }

    boolean isDone() {
        return status == Status.DONE;
    }

    boolean matches(String requestHash) {
        return this.requestHash.equals(requestHash);
    }
}
