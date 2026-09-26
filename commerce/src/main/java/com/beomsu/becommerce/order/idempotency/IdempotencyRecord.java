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

    /**
     * 처리권 만료 시각(#369). PROCESSING 인 채로 이 시각이 지나면 처리하던 요청이 죽은 것으로 보고
     * 다음 같은 키 요청이 넘겨받는다. 살아 있는 느린 요청을 가로채지 않도록 요청 하나의 최악 시간보다 길게 둔다.
     */
    @Column(nullable = false)
    private Instant leaseUntil;

    /** 넘겨받기와 저장을 가르는 버전. 처리권을 잃은 원래 요청의 늦은 저장은 여기서 막힌다. */
    @Version
    private long version;

    private IdempotencyRecord(String key, String apiPath, String httpMethod, String requestHash,
                              Instant now, Duration lease) {
        this.idempotencyKey = key;
        this.apiPath = apiPath;
        this.httpMethod = httpMethod;
        this.requestHash = requestHash;
        this.status = Status.PROCESSING;
        this.createdAt = now;
        this.expiresAt = now.plus(TTL);
        this.leaseUntil = now.plus(lease);
    }

    static IdempotencyRecord start(String key, String apiPath, String httpMethod, String requestHash) {
        return start(key, apiPath, httpMethod, requestHash, Instant.now(), IdempotencyService.DEFAULT_LEASE);
    }

    static IdempotencyRecord start(String key, String apiPath, String httpMethod, String requestHash,
                                   Instant now, Duration lease) {
        return new IdempotencyRecord(key, apiPath, httpMethod, requestHash, now, lease);
    }

    /** 처리 중인데 처리권이 만료됐나. 처리하던 요청이 죽었다고 볼 수 있는 상태다. */
    boolean leaseExpired(Instant now) {
        return status == Status.PROCESSING && leaseUntil.isBefore(now);
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
