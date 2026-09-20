package com.beomsu.becommerce.settlement.internal;

/** 정산 조정 항목의 상태. */
public enum SettlementAdjustmentStatus {
    /** 회수 대기 — 차기 정산이 집어간다. */
    PENDING,
    /** 차기 정산에 음수로 반영됨. */
    APPLIED,
    /** 자동 반영 불가(회수액이 차기 정산 총액을 넘음) — 사람이 처리해야 한다. */
    REVIEW_REQUIRED
}
