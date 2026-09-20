package com.beomsu.becommerce.dispute.internal;

import java.time.Instant;

/** 분쟁 조회 뷰 — 엔티티 대신 노출한다. */
public record DisputeView(
        Long id,
        String chargebackId,
        String orderNo,
        String status,
        long amount,
        Instant respondByDeadline,
        String evidenceMemo,
        Instant resolvedAt) {

    public static DisputeView from(Dispute d) {
        return new DisputeView(d.getId(), d.getChargebackId(), d.getOrderNo(), d.getStatus().name(),
                d.getAmount(), d.getRespondByDeadline(), d.getEvidenceMemo(), d.getResolvedAt());
    }
}
