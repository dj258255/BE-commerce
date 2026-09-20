package com.beomsu.becommerce.subscription.billing;

import com.beomsu.becommerce.shared.crypto.BlindIndexer;
import com.beomsu.becommerce.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 빌링키 — 카드번호·유효기간·CVC를 암호화한 토큰. 최초 1회 인증 후 매 주기 무인증 결제에 쓴다.
 *
 * <p>{@code customerKey}와 빌링키의 <b>이중 키 구조</b>가 보안의 핵심이다. 빌링키 단독이 유출돼도
 * customerKey 없이는 결제할 수 없다.
 */
@Entity
@Table(name = "billing_keys")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BillingKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 빌링키 토큰. 단독 유출로는 결제 불가(customerKey와 이중 키). 민감정보라 저장 시 envelope
     * 암호화(@Convert)한다. 암호문은 매번 달라 유니크·동등검색이 불가능하므로 유니크는 아래
     * {@link #billingKeyIndex}(블라인드 인덱스)로 이전했고, 컬럼 길이도 암호문에 맞춰 600으로 넓혔다.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 600)
    private String billingKey;

    /**
     * 빌링키의 블라인드 인덱스(HMAC-SHA256, 결정적). 암호화로 잃은 조회·유니크를 대체한다 —
     * 같은 빌링키는 항상 같은 인덱스가 되어 {@code findByBillingKeyIndex}로 찾고, 여기에 유니크 제약을 둔다.
     * 서비스가 {@code BlindIndexer.index(billingKey)}로 계산해 넘긴다(엔티티는 secret을 몰라야 한다).
     */
    @Column(nullable = false, unique = true, length = 64)
    private String billingKeyIndex;

    /**
     * 고객 식별자. <b>반드시 UUID 같은 무작위 값</b>을 저장한다.
     * 이메일·자동증가 ID는 금지 — 예측 가능한 값이면 이중 키 구조의 방어력이 무너진다.
     * 비밀이 아닌 공개 식별자(이중 키의 공개 절반)라 암호화하지 않는다.
     */
    @Column(nullable = false, length = 64)
    private String customerKey;

    @Column(nullable = false)
    private long userId;

    @Column(nullable = false)
    private Instant createdAt;

    /**
     * 이 키를 아직 쓸 수 있는가.
     *
     * <p><b>왜 상태가 필요한가</b>: 빌링키는 <b>카드번호의 대체물</b>이다. 카드가 재발급되거나
     * 고객이 구독을 해지하면 그 대체물도 같이 죽어야 한다. 그런데 발급만 하고 폐기가 없으면
     * <b>해지한 고객의 결제 수단을 계속 들고 있게 된다.</b>
     *
     * <p>지우지 않고 상태로 남기는 이유는 이 프로젝트의 다른 자리와 같다 — 지우면 왜 못 쓰게
     * 됐는지를 나중에 답할 수 없다.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BillingKeyStatus status = BillingKeyStatus.ACTIVE;

    /** 폐기 사유. 카드가 죽어서인지 고객이 해지해서인지는 대응이 다르다. */
    @Column(length = 100)
    private String revokeReason;

    private Instant revokedAt;

    private BillingKey(String billingKey, String billingKeyIndex, String customerKey, long userId) {
        this.status = BillingKeyStatus.ACTIVE;
        this.billingKey = billingKey;
        this.billingKeyIndex = billingKeyIndex;
        this.customerKey = customerKey;
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    /**
     * 빌링키 발급. {@code billingKeyIndex}는 서비스가 {@code BlindIndexer.index(billingKey)}로 미리
     * 계산해 넘긴다(엔티티가 blind-index secret 빈을 주입받지 않게 하기 위함).
     */
    public static BillingKey of(String billingKey, String billingKeyIndex, String customerKey, long userId) {
        return new BillingKey(billingKey, billingKeyIndex, customerKey, userId);
    }

    public boolean isActive() {
        return status == BillingKeyStatus.ACTIVE;
    }

    /**
     * 폐기한다. <b>이미 폐기된 키를 다시 폐기해도 처음 사유를 덮어쓰지 않는다</b> —
     * 카드가 죽어 폐기된 키를 나중에 해지로 다시 폐기하면, 진짜 이유가 지워진다.
     */
    public void revoke(String reason) {
        if (status == BillingKeyStatus.REVOKED) {
            return;
        }
        this.status = BillingKeyStatus.REVOKED;
        this.revokeReason = reason;
        this.revokedAt = Instant.now();
    }
}
