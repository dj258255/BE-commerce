package com.beomsu.becommerce.receipt;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 현금영수증 — 비동기 발급 상태머신(REQUESTED → ISSUED/FAILED → CANCELED).
 *
 * <p>운영 함정: 수동 발급 건은 결제가 취소되면 <b>가맹점이 직접 현금영수증도 취소</b>해야 한다.
 * 이 시스템은 결제 취소 이벤트를 구독해 자동으로 연쇄 취소한다({@code ReceiptService.cancelByPayment}).
 */
@Entity
@Table(name = "cash_receipts")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class CashReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String orderNo;

    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 20)
    private String receiptType;   // DEDUCTION(소득공제) / EXPENSE(지출증빙)

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CashReceiptStatus status;

    @Column(length = 100)
    private String receiptKey;    // 발급 성공 시 PG가 부여

    @Column(nullable = false)
    private Instant createdAt;

    private Instant issuedAt;

    private CashReceipt(String orderNo, long amount, String receiptType) {
        this.orderNo = orderNo;
        this.amount = amount;
        this.receiptType = receiptType;
        this.status = CashReceiptStatus.REQUESTED;
        this.createdAt = Instant.now();
    }

    static CashReceipt request(String orderNo, long amount, String receiptType) {
        return new CashReceipt(orderNo, amount, receiptType);
    }

    /** 발급 완료(비동기 콜백/응답). */
    void markIssued(String receiptKey) {
        requireStatus(CashReceiptStatus.REQUESTED);
        this.receiptKey = receiptKey;
        this.status = CashReceiptStatus.ISSUED;
        this.issuedAt = Instant.now();
    }

    void markFailed() {
        requireStatus(CashReceiptStatus.REQUESTED);
        this.status = CashReceiptStatus.FAILED;
    }

    /** 결제 취소에 따른 연쇄 취소. 이미 취소면 멱등하게 무시. */
    void cancel() {
        if (status == CashReceiptStatus.CANCELED) {
            return;
        }
        this.status = CashReceiptStatus.CANCELED;
    }

    private void requireStatus(CashReceiptStatus expected) {
        if (status != expected) {
            throw new IllegalStateException(
                    "현금영수증 상태 전이 불가: %s에서 요청됨".formatted(status));
        }
    }
}
