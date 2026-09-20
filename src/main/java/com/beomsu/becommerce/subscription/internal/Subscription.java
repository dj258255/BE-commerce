package com.beomsu.becommerce.subscription.internal;

import com.beomsu.becommerce.subscription.billing.BillingKey;
import com.beomsu.becommerce.subscription.billing.BillingCycle;
import com.beomsu.becommerce.shared.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * 구독 애그리거트.
 *
 * <p>상태 전이는 가드({@link #transitionTo})를 통해서만 일어나며 {@link SubscriptionStatus}의 허용
 * 전이표를 위반하면 예외가 난다. 청구 성공은 {@link #renew}로 다음 주기를 예약하고, 실패는 dunning
 * 단계({@link #enterGrace} → {@link #hold})를 밟는다. 낙관적 락({@code @Version})으로 배치와 사용자
 * 요청의 동시 변경을 감지한다.
 */
@Entity
@Table(name = "subscriptions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Subscription {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private long userId;

    /**
     * 청구에 사용할 빌링키(참조 문자열) — {@link BillingKey#getBillingKey()}. 민감정보라 저장 시
     * envelope 암호화(@Convert)한다. 값으로 조회하지 않으므로(userId·status·nextBillingDate로만 조회)
     * 블라인드 인덱스는 불필요. 청구 시 {@code getBillingKey()}는 컨버터가 자동 복호화한 원문을 돌려준다.
     * 암호문에 맞춰 컬럼 길이를 600으로 넓혔다.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(nullable = false, length = 600)
    private String billingKey;

    /** 주기당 청구 금액(원). 플랜 변경 시 {@link #changePlan}로 갱신. */
    @Column(nullable = false)
    private long planAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SubscriptionStatus status;

    /** 현재 청구 주기의 시작일 — proration의 기준. */
    @Column(nullable = false)
    private LocalDate currentPeriodStart;

    /** 다음 청구 예정일 — 배치가 이 날짜로 청구 대상을 조회한다. */
    @Column(nullable = false)
    private LocalDate nextBillingDate;

    /**
     * 원래 청구하기로 한 일자(1~31). 그 달에 그 날이 없으면 말일로 클램프한다.
     *
     * <p><b>왜 따로 두는가</b>: 다음 청구일을 직전 청구일에서 더하면, 2월에 한 번 당겨진 날이
     * 영영 돌아오지 않는다(1/31 → 2/28 → 3/28). 앵커를 들고 매달 다시 계산해야
     * 1/31 → 2/28 → 3/31 이 된다. {@link BillingCycle} 참고.
     */
    @Column(nullable = false)
    private int anchorDay;

    @Version
    private long version;

    private Subscription(long userId, String billingKey, long planAmount,
                         LocalDate currentPeriodStart, LocalDate nextBillingDate, int anchorDay) {
        this.userId = userId;
        this.billingKey = billingKey;
        this.planAmount = planAmount;
        this.status = SubscriptionStatus.ACTIVE;
        this.currentPeriodStart = currentPeriodStart;
        this.nextBillingDate = nextBillingDate;
        this.anchorDay = anchorDay;
    }

    /** 구독 개시 — ACTIVE로 생성한다. 앵커는 시작일의 일자다. */
    public static Subscription create(long userId, String billingKey, long planAmount,
                                      LocalDate currentPeriodStart, LocalDate nextBillingDate) {
        return new Subscription(userId, billingKey, planAmount, currentPeriodStart, nextBillingDate,
                BillingCycle.anchorOf(currentPeriodStart));
    }

    /**
     * 청구 성공 → ACTIVE 유지, 다음 주기를 예약한다. 방금 청구한 주기의 시작일을 현재 주기 시작으로
     * 옮기고 다음 청구일을 갱신한다. ACTIVE에서만 호출할 수 있다(유예/정지 구독은 먼저 복귀해야 함).
     */
    public void renew(LocalDate nextBillingDate) {
        requireActive();
        this.currentPeriodStart = this.nextBillingDate;
        this.nextBillingDate = nextBillingDate;
    }

    /** ACTIVE → IN_GRACE_PERIOD. soft decline 첫 실패 시 유예기간 진입. */
    public void enterGrace() {
        transitionTo(SubscriptionStatus.IN_GRACE_PERIOD);
    }

    /** → ON_HOLD. 재시도 소진(유예에서) 또는 hard decline(ACTIVE 직행) 시 정지. */
    public void hold() {
        transitionTo(SubscriptionStatus.ON_HOLD);
    }

    /** IN_GRACE_PERIOD/ON_HOLD → ACTIVE. 청구 성공으로 정상 복귀. */
    public void recover() {
        transitionTo(SubscriptionStatus.ACTIVE);
    }

    /** ACTIVE → CANCELED. 사용자/가맹점의 해지. */
    public void cancel() {
        transitionTo(SubscriptionStatus.CANCELED);
    }

    /** ON_HOLD → EXPIRED. 정지 기간에도 회수 실패 시 종료. */
    public void expire() {
        transitionTo(SubscriptionStatus.EXPIRED);
    }

    /** 플랜 변경 — 청구 금액을 갱신한다(일할계산은 서비스가 담당). ACTIVE에서만 가능. */
    public void changePlan(long newAmount) {
        requireActive();
        this.planAmount = newAmount;
    }

    private void transitionTo(SubscriptionStatus target) {
        if (!status.canTransitionTo(target)) {
            throw SubscriptionException.invalidTransition(status, target);
        }
        this.status = target;
    }

    private void requireActive() {
        if (status != SubscriptionStatus.ACTIVE) {
            throw SubscriptionException.notActive(status);
        }
    }
}
