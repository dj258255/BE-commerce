package com.beomsu.becommerce.settlement.internal;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 정산 어드민 조회용 뷰 — 엔티티 대신 노출한다(다른 모듈 어드민과 일관).
 */
public record SettlementView(
        Long id,
        LocalDate settlementDate,
        long grossAmount,
        long feeAmount,
        long feeVatAmount,
        long netAmount,
        int itemCount,
        SettlementStatus status,
        LocalDate payoutDate,
        Instant paidOutAt,
        String payoutInstructionReference,
        PayoutReconciliationStatus payoutReconciliationStatus,
        String payoutReportReference,
        String payoutReportCurrency,
        Long payoutReportAmount,
        Instant payoutReconciledAt,
        String payoutReconciliationReason) {

    public static SettlementView from(Settlement s) {
        return new SettlementView(s.getId(), s.getSettlementDate(), s.getGrossAmount(),
                s.getFeeAmount(), s.getFeeVatAmount(), s.getNetAmount(), s.getItemCount(),
                s.getStatus(), s.getPayoutDate(), s.getPaidOutAt(),
                s.getPayoutInstructionReference(), s.getPayoutReconciliationStatus(),
                s.getPayoutReportReference(), s.getPayoutReportCurrency(), s.getPayoutReportAmount(),
                s.getPayoutReconciledAt(), s.getPayoutReconciliationReason());
    }
}
