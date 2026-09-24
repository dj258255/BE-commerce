package com.beomsu.becommerce.payment.internal;

import com.beomsu.becommerce.payment.PaymentStatus;
import com.beomsu.becommerce.payment.PaymentService;
import com.beomsu.becommerce.payment.PaymentException;
import com.beomsu.becommerce.shared.Money;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 결제 애그리거트.
 *
 * <p>상태 전이는 {@link #transitionTo}를 통해서만 일어나며, {@link PaymentStatus}의 허용 전이표를
 * 위반하면 예외가 난다. 모든 전이는 {@link PaymentHistory}에 append-only로 기록된다.
 * 낙관적 락({@code @Version})으로 동시 전이를 감지한다.
 */
@Entity
@Table(name = "payments")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String orderNo;

    @Column(length = 200)
    private String paymentKey;

    /**
     * 같은 카드를 결제 여러 건에 걸쳐 묶는 키. 승인 성공 때 PG 응답에서 받아 적는다.
     *
     * <p><b>카드 데이터가 아니다.</b> PG 가 이미 마스킹해 준 번호와 발급사 코드를 단방향 해시로
     * 바꾼 값이고 원문은 어디에도 안 남는다. 카드 결제가 아니거나 PG 가 카드 정보를 안 주면
     * null 이고, 그때 사후 탐지는 예전처럼 {@code paymentKey} 로 떨어진다.
     */
    @Column(length = 64)
    private String cardFingerprint;

    @Column(nullable = false)
    private long amount;

    /** 취소 가능 잔액. 부분취소마다 차감된다. */
    @Column(nullable = false)
    private long balanceAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PaymentStatus status;

    @Column(length = 30)
    private String method;

    @Column(nullable = false, length = 30)
    private String pgProvider = "TOSS_PAYMENTS";

    /** UNKNOWN 진입 사유 (타임아웃/5xx 등) */
    @Column(length = 200)
    private String unknownReason;

    /**
     * 지금까지 <b>반영된</b> 취소 건수. 다음 취소의 순번은 이 값 + 1이다.
     *
     * <p>취소는 한 결제에 여러 번 일어나므로 "결제 + 금액"으로는 취소 건을 식별할 수 없다. 이 순번이
     * PG 멱등키·취소 반영 가드·원장 중복 판정의 공통 키가 된다. 실패한 취소는 올리지 않으므로
     * 재시도가 같은 순번을 다시 계산한다(재시도는 멱등, 서로 다른 취소는 분리).
     */
    @Column(nullable = false)
    private int cancelCount;

    @Version
    private long version;

    @Column(nullable = false)
    private Instant requestedAt;

    private Instant approvedAt;

    /** 복구가 확정하지 못한 횟수(#248). PG 가 "진행 중"이라 답했거나 조회가 실패한 경우다. */
    @Column(nullable = false)
    private int recoveryAttempts;

    /** 다음에 다시 물어볼 시각. backoff 정책만 채운다. null 이면 바로 대상이다. */
    private Instant recoveryNextAt;

    @OneToMany(mappedBy = "payment", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private final List<PaymentHistory> histories = new ArrayList<>();

    /**
     * 할부 개월. <b>0이면 일시불</b>이고, 그 밖에는 카드사에 요청한 개월 수다.
     *
     * <p><b>이 값은 우리 정산 금액을 바꾸지 않는다.</b> 고객이 12개월로 나눠 내도 카드사가
     * 가맹점에는 일시에 전액을 지급하고, 분납은 카드사와 고객 사이의 일이다. 그래서 대사·정산은
     * 이 값을 보지 않는다. 그럼에도 저장하는 이유는 둘이다 — 운영자가 대사 화면에서 건을 판단할 때
     * 필요하고, 이상거래 판정이 이 값을 본다(고액을 한도 안에서 키우는 가장 쉬운 수단이 할부다).
     */
    @Column(nullable = false)
    private int installmentMonths;

    private Payment(String orderNo, long amount, int installmentMonths) {
        this.orderNo = orderNo;
        this.amount = amount;
        this.balanceAmount = amount;
        this.status = PaymentStatus.READY;
        this.requestedAt = Instant.now();
        this.installmentMonths = installmentMonths;
    }

    /** 결제 시작 — READY 상태로 생성한다. 일시불. */
    public static Payment initiate(String orderNo, Money amount) {
        return initiate(orderNo, amount, 0);
    }

    /**
     * 결제 시작 — 할부 개월을 함께 받는다. 값을 <b>여기서</b> 검증하는 이유는, 승인 요청을
     * 내보낸 뒤에 카드사가 거절하면 그때는 이미 우리 쪽에 IN_PROGRESS 행이 남기 때문이다.
     * 우리가 미리 알 수 있는 것은 미리 막는다.
     */
    public static Payment initiate(String orderNo, Money amount, int installmentMonths) {
        validateInstallment(amount.minorUnit(), installmentMonths);
        return new Payment(orderNo, amount.minorUnit(), installmentMonths);
    }

    /** 할부 최대 개월. 토스페이먼츠 승인 응답의 {@code installmentPlanMonths} 범위와 맞춘다. */
    private static final int MAX_INSTALLMENT_MONTHS = 12;

    /** 국내 카드사 공통 — 건당 5만원 이상일 때만 할부가 걸린다. */
    private static final long MIN_INSTALLMENT_AMOUNT = 50_000L;

    private static void validateInstallment(long amount, int months) {
        if (months == 0) {
            return;                       // 일시불
        }
        if (months < 0 || months > MAX_INSTALLMENT_MONTHS) {
            throw new PaymentException("INVALID_INSTALLMENT",
                    "할부 개월은 0(일시불) 또는 1~%d 이어야 합니다: %d"
                            .formatted(MAX_INSTALLMENT_MONTHS, months));
        }
        if (amount < MIN_INSTALLMENT_AMOUNT) {
            throw new PaymentException("INVALID_INSTALLMENT",
                    "%,d원 미만은 할부가 되지 않습니다: %,d원".formatted(MIN_INSTALLMENT_AMOUNT, amount));
        }
    }

    /** READY → IN_PROGRESS. paymentKey를 귀속시키고 승인을 시작한다. */
    public void startApproval(String paymentKey) {
        this.paymentKey = paymentKey;
        transitionTo(PaymentStatus.IN_PROGRESS, TriggeredBy.USER, "승인 요청");
    }

    /** IN_PROGRESS → DONE. */
    public void approve(String method) {
        approve(method, null);
    }

    /**
     * IN_PROGRESS → DONE. 어느 PG가 승인했는지 함께 적는다.
     *
     * <p>이 값이 나중에 취소를 어디로 보낼지 정한다. 이중화를 켜면 승인이 다른 PG로 넘어갈 수
     * 있는데, 취소를 아무 PG에나 보내면 "그런 거래 없음"이 돌아오고 그것이 취소 결과가 된다.
     * 고객 돈은 원 PG에 잡혀 있는데 우리 장부에는 취소로 남는다.
     *
     * <p>{@code provider}가 null 이면 기존 값을 그대로 둔다. 단일 PG 어댑터는 자기 이름을
     * 알려 주지 않는다.
     */
    public void approve(String method, String provider) {
        approve(method, provider, null);
    }

    /** 승인하면서 카드 지문까지 적는다. 지문이 null 이면 기존 값을 덮지 않는다. */
    public void approve(String method, String provider, String cardFingerprint) {
        transitionTo(PaymentStatus.DONE, TriggeredBy.USER, "승인 완료");
        this.method = method;
        if (cardFingerprint != null) {
            this.cardFingerprint = cardFingerprint;
        }
        if (provider != null) {
            this.pgProvider = provider;
        }
        this.approvedAt = Instant.now();
    }

    /** IN_PROGRESS → UNKNOWN. 타임아웃 등 미확정 상태. 복구 배치/망취소의 대상이 된다. */
    public void markUnknown(String reason) {
        this.unknownReason = reason;
        transitionTo(PaymentStatus.UNKNOWN, TriggeredBy.USER, reason);
    }

    /** → ABORTED. 승인 실패. */
    public void abort(String reason) {
        transitionTo(PaymentStatus.ABORTED, TriggeredBy.USER, reason);
    }

    // --- 복구(recovery) 경로: UNKNOWN 결제를 배치가 조회 후 확정한다 ---

    /** UNKNOWN → DONE. PG 조회 결과 실제로는 승인돼 있었을 때(복구 전진). */
    public void confirmByRecovery(String method) {
        transitionTo(PaymentStatus.DONE, TriggeredBy.RECOVERY_BATCH, "복구: PG 승인 확인");
        this.method = method;
        this.approvedAt = Instant.now();
    }

    /**
     * 확정하지 못한 복구 시도를 남긴다(상태는 그대로 UNKNOWN). {@code nextAt} 이 있으면 그때까지 복구 대상에서 빠진다.
     */
    public void recordRecoveryAttempt(Instant nextAt) {
        this.recoveryAttempts++;
        this.recoveryNextAt = nextAt;
    }

    /** UNKNOWN → ABORTED. PG에 결제 정보가 없을 때(승인이 실제로 안 됨). */
    public void abortByRecovery(String reason) {
        transitionTo(PaymentStatus.ABORTED, TriggeredBy.RECOVERY_BATCH, reason);
    }

    /**
     * UNKNOWN → CANCELED. <b>PG가 이미 취소한 사실을 우리 쪽에 반영한다.</b>
     *
     * <p>복구 배치가 미확정 결제를 조회했더니 PG 상태가 CANCELED인 경우에만 호출된다.
     * 취소 요청을 보내는 메서드가 아니다 — 실제 취소 전송은 {@code PaymentService.cancelByOrderNo}와
     * 보상 태스크가 한다.
     */
    public void markCanceledByPg(String reason) {
        transitionTo(PaymentStatus.CANCELED, TriggeredBy.RECOVERY_BATCH, reason);
        this.balanceAmount = 0;
    }

    /**
     * 취소(전액/부분). 잔액을 초과하면 예외. 잔액이 0이 되면 CANCELED, 남으면 PARTIAL_CANCELED.
     */
    public void cancel(Money cancelAmount, TriggeredBy by, String reason) {
        validateCancelable(cancelAmount);
        long newBalance = balanceAmount - cancelAmount.minorUnit();
        transitionTo(cancelTargetOf(newBalance), by, reason);
        this.balanceAmount = newBalance;
        this.cancelCount++;
    }

    /**
     * 취소 가능 여부만 검사한다(상태 변경 없음). 취소 사가 1단계가 <b>PG를 부르기 전에</b> 불가능한
     * 취소를 걸러내는 데 쓴다. {@link #cancel}도 같은 검사를 통과한 뒤 전이하므로 규칙이 한 곳에 있다.
     */
    public void validateCancelable(Money cancelAmount) {
        if (cancelAmount.minorUnit() > balanceAmount) {
            throw PaymentException.cancelAmountExceeded(cancelAmount.minorUnit(), balanceAmount);
        }
        PaymentStatus target = cancelTargetOf(balanceAmount - cancelAmount.minorUnit());
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(status, target);
        }
    }

    /** 취소 후 잔액이 0이면 전액 취소, 남으면 부분 취소. */
    private static PaymentStatus cancelTargetOf(long newBalance) {
        return (newBalance == 0) ? PaymentStatus.CANCELED : PaymentStatus.PARTIAL_CANCELED;
    }

    private void transitionTo(PaymentStatus target, TriggeredBy by, String reason) {
        if (!status.canTransitionTo(target)) {
            throw PaymentException.invalidTransition(status, target);
        }
        histories.add(PaymentHistory.of(this, status, target, by, reason));
        this.status = target;
    }

    public Money amountAsMoney() {
        return Money.krw(amount);
    }

    public Money balanceAsMoney() {
        return Money.krw(balanceAmount);
    }

    public List<PaymentHistory> getHistories() {
        return List.copyOf(histories);
    }
}
