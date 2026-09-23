package com.beomsu.becommerce.settlement.internal;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 일 단위 정산 — 하루치 거래를 집계한 가맹점 지급금 묶음.
 *
 * <p>불변식: <b>netAmount = grossAmount - feeAmount - feeVatAmount</b>. 생성 시점에 강제하며, 위반하면
 * 예외가 난다. {@code settlementDate} 유니크로 같은 날짜 정산이 두 번 만들어지는 것을 DB가 차단한다 —
 * 배치 재실행 멱등성의 핵심.
 *
 * <p>수수료는 수수료(feeAmount)와 그 부가세(feeVatAmount)로 나눠 잡는다(실무형). 지급예정일
 * ({@code payoutDate})은 정산일 + N영업일이며, 지급 확정 시각({@code paidOutAt})은 어드민이
 * {@link #markPaidOut()}으로 채운다.
 */
@Entity
@Table(name = "settlements",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_settlement_date_currency_seller",
                columnNames = {"settlementDate", "currency", "seller_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Settlement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate settlementDate;

    /**
     * 정산 통화(ISO 4217).
     *
     * <p>정산은 <b>통화별로 따로</b> 만든다. 하루치 합계를 하나로 두면 KRW 와 USD 가 한 숫자에
     * 섞여 지급액이 뜻을 잃는다. 그래서 유니크도 {@code (settlementDate, currency)}다.
     */
    @Column(nullable = false, length = 3)
    private String currency;

    /**
     * 정산을 받을 판매자. <b>플랫폼 직판도 자기 판매자 행을 갖는다</b>(V49).
     *
     * <p><b>유니크 키에 들어 있다.</b> 같은 날 같은 통화라도 판매자가 다르면 정산이 따로 난다.
     * 예전에는 플랫폼 직판을 {@code null} 로 적었는데, MySQL 유니크 인덱스가 {@code NULL} 을
     * 서로 다른 값으로 봐서 <b>제약이 그 자리만 안 걸렸다.</b> 지금은 값이 늘 있어서
     * 제약 하나로 끝나고, 존재 검사도 갈리지 않는다.
     */
    @Column(name = "seller_id", nullable = false)
    private long sellerId;

    /** 거래 총액 */
    @Column(nullable = false)
    private long grossAmount;

    /** 수수료 합 */
    @Column(nullable = false)
    private long feeAmount;

    /** 수수료 부가세(수수료의 10%) */
    @Column(nullable = false)
    private long feeVatAmount;

    /** 지급액 = gross - fee - feeVat (불변식 검증 대상) */
    @Column(nullable = false)
    private long netAmount;

    @Column(nullable = false)
    private int itemCount;

    /** 지급예정일 = settlementDate + N영업일(주말 skip). 신규 집계는 항상 계산해 채운다. */
    @Column(nullable = false)
    private LocalDate payoutDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SettlementStatus status;

    @Column(nullable = false)
    private Instant createdAt;

    /** 지급 확정 시각(nullable) — 어드민이 지급을 확정한 순간. 미확정이면 null. */
    private Instant paidOutAt;

    /** 지급을 막은 이유. 안 막았으면 null. */
    @Column(length = 200)
    private String payoutHoldReason;

    /** 외부 지급 report가 되돌려야 하는 내부 지급 지시 reference. */
    @Column(nullable = false, unique = true, length = 100)
    private String payoutInstructionReference;

    /** 마지막 외부 지급 report의 대사 결과. MATCHED일 때만 PAID_OUT으로 전이할 수 있다. */
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private PayoutReconciliationStatus payoutReconciliationStatus;

    /** 마지막으로 관측한 외부 report reference. 누락이면 null이다. */
    @Column(length = 100)
    private String payoutReportReference;

    @Column(length = 3)
    private String payoutReportCurrency;

    private Long payoutReportAmount;

    private Instant payoutReconciledAt;

    @Column(length = 200)
    private String payoutReconciliationReason;

    /**
     * 회수 조정을 반영해 금액을 다시 쓴다. <b>생성 직후, 지급 전에만</b> 부른다.
     *
     * <p>이미 지급된 정산을 고치지 않는 게 이 설계의 요점이라, 여기서도 CREATED 밖에서는 막는다.
     */
    void reviseAmounts(long gross, long fee, long feeVat) {
        if (this.status != SettlementStatus.CREATED) {
            throw new IllegalStateException("지급 확정된 정산은 금액을 고칠 수 없습니다: " + this.status);
        }
        this.grossAmount = gross;
        this.feeAmount = fee;
        this.feeVatAmount = feeVat;
        this.netAmount = gross - fee - feeVat;
    }

    private Settlement(LocalDate settlementDate, String currency, long grossAmount, long feeAmount,
                       long feeVatAmount, int itemCount, LocalDate payoutDate) {
        this.settlementDate = settlementDate;
        this.currency = currency;
        this.grossAmount = grossAmount;
        this.feeAmount = feeAmount;
        this.feeVatAmount = feeVatAmount;
        this.netAmount = grossAmount - feeAmount - feeVatAmount;
        this.itemCount = itemCount;
        this.payoutDate = payoutDate;
        this.status = SettlementStatus.CREATED;
        this.createdAt = Instant.now();
        this.payoutInstructionReference = instructionReference(settlementDate, currency, sellerId);
    }

    /**
     * 하루치 집계로 정산을 만든다. netAmount는 gross - fee - feeVat로 계산하며, 불변식
     * (net = gross - fee - feeVat)을 생성 직후 재검증한다 — 불균형 정산은 만들어질 수 없다.
     */
    /** @param sellerId 정산을 받을 판매자. 플랫폼 직판이면 플랫폼 판매자 id 다 */
    public static Settlement of(LocalDate settlementDate, String currency, long grossAmount, long feeAmount,
                                long feeVatAmount, int itemCount, LocalDate payoutDate, Long sellerId) {
        if (grossAmount < 0 || feeAmount < 0 || feeVatAmount < 0) {
            throw new IllegalArgumentException("금액은 음수일 수 없습니다: gross=%d, fee=%d, feeVat=%d"
                    .formatted(grossAmount, feeAmount, feeVatAmount));
        }
        if (feeAmount + feeVatAmount > grossAmount) {
            throw new IllegalArgumentException("수수료+부가세가 총액보다 클 수 없습니다: gross=%d, fee=%d, feeVat=%d"
                    .formatted(grossAmount, feeAmount, feeVatAmount));
        }
        Settlement settlement = new Settlement(settlementDate, currency, grossAmount, feeAmount, feeVatAmount, itemCount, payoutDate);
        settlement.sellerId = sellerId;
        if (settlement.netAmount != grossAmount - feeAmount - feeVatAmount) {
            throw new IllegalStateException("정산 불변식 위반: net(%d) ≠ gross(%d) - fee(%d) - feeVat(%d)"
                    .formatted(settlement.netAmount, grossAmount, feeAmount, feeVatAmount));
        }
        return settlement;
    }

    /**
     * 지급을 확정한다 — CREATED일 때만 PAID_OUT으로 전이하고 지급 확정 시각을 찍는다.
     *
     * <p>멱등: 이미 PAID_OUT이면 무시한다(중복 확정 방어). createdAt과 동일하게 {@code Instant.now()}로
     * 시각을 스냅샷한다.
     *
     * @return 이번 호출에서 실제로 전이했으면 true. 호출자가 이 값으로 지급 이벤트 발행 여부를
     *         정한다 — 이미 지급된 건에 이벤트를 다시 쏘면 원장이 같은 회수를 두 번 볼 뻔한다
     *         (원장도 멱등하지만, 일어나지 않은 사건을 알리지 않는 것이 맞다).
     */
    public boolean markPaidOut() {
        // <b>보류와 이미 지급됨을 구별한다.</b> 둘 다 false 로 돌려주면 화면이 "이미 지급됐다"로
        // 읽어 사람이 심사가 걸린 것을 모른 채 넘어간다. 막힌 이유는 말해 줘야 한다.
        if (this.status == SettlementStatus.PAYOUT_HELD) {
            throw new IllegalStateException("지급이 보류된 정산이다 id=" + id + " 이유=" + payoutHoldReason);
        }
        if (this.status == SettlementStatus.CREATED) {
            if (this.payoutReconciliationStatus != PayoutReconciliationStatus.MATCHED) {
                throw new IllegalStateException("외부 지급 대사가 MATCHED가 아니어서 확정할 수 없습니다: "
                        + this.payoutReconciliationStatus);
            }
            this.status = SettlementStatus.PAID_OUT;
            this.paidOutAt = Instant.now();
            return true;
        }
        return false;
    }

    /**
     * 판매자 심사에 걸려 지급을 막는다. <b>집계는 그대로 두고 상태만 바꾼다.</b>
     *
     * @param reason 왜 막았는지. 화면에 그대로 나가므로 사람이 읽을 말이어야 한다
     */
    public void holdPayout(String reason) {
        if (this.status == SettlementStatus.PAID_OUT) {
            throw new IllegalStateException("이미 지급된 정산은 못 막는다 id=" + id);
        }
        this.status = SettlementStatus.PAYOUT_HELD;
        this.payoutHoldReason = reason;
    }

    /** 심사가 풀렸다. 사람이 확인하고 되돌린다. */
    public void releasePayoutHold() {
        if (this.status != SettlementStatus.PAYOUT_HELD) {
            throw new IllegalStateException("보류 상태가 아니다 id=" + id + " status=" + status);
        }
        this.status = SettlementStatus.CREATED;
        this.payoutHoldReason = null;
    }

    /**
     * 외부 지급 report의 판정과 원문 reference를 저장한다.
     *
     * <p>대사 결과를 먼저 저장하고 지급 상태 전이는 호출자가 MATCHED인 경우에만 수행한다.
     * 따라서 pending·불일치도 사라지지 않고 운영자가 재처리할 수 있다.
     */
    public void recordPayoutReconciliation(PayoutReconciliationEngine.Result result,
                                            String observedReference) {
        if (!payoutInstructionReference.equals(result.payoutReference())) {
            throw new IllegalArgumentException("정산의 지급 지시 reference와 대사 결과가 다릅니다: "
                    + payoutInstructionReference + " != " + result.payoutReference());
        }
        if (this.status == SettlementStatus.PAID_OUT) {
            if (this.payoutReconciliationStatus == PayoutReconciliationStatus.MATCHED
                    && this.payoutReconciliationStatus == result.status()) {
                return; // 이미 확정된 동일한 report 재수집은 멱등 처리
            }
            throw new IllegalStateException("이미 지급된 정산의 대사 결과를 바꿀 수 없습니다: " + id);
        }
        this.payoutReconciliationStatus = result.status();
        this.payoutReportReference = observedReference;
        this.payoutReportCurrency = result.currency();
        this.payoutReportAmount = result.actualAmount();
        this.payoutReconciledAt = Instant.now();
        this.payoutReconciliationReason = result.reason();
    }

    private static String instructionReference(LocalDate settlementDate, String currency, long sellerId) {
        return "SETTLEMENT-%s-%s-%d".formatted(settlementDate, currency, sellerId);
    }
}
