package com.beomsu.becommerce.settlement.internal;

import com.beomsu.becommerce.escrow.EscrowReleasedEvent;

/**
 * 정산 대상 항목의 생명주기 — 에스크로에 정렬된다.
 *
 * <p>에스크로의 약속("구매확정 전까지 판매자 정산 보류")을 정산에 그대로 반영하기 위해, 결제 승인만으로는
 * 정산 가능이 되지 않는다. 승인 항목은 {@link #PENDING_CONFIRMATION}(구매확정 대기)으로 적재되고,
 * 에스크로 릴리스(={@code EscrowReleasedEvent}, 구매확정)가 와야 {@link #CONFIRMED}(정산 가능)로
 * 전이한다. 배치는 CONFIRMED만 집계해 {@link #SETTLED}로 넘기므로, 구매확정 전 항목은 지급에서 빠진다
 * (=보류의 실현). 구매확정 전 전액취소는 {@link #CANCELED}로 정산에서 제외한다.
 *
 * <p>선언 순서 = DB enum 순서(Flyway V9)와 일치시켜 {@code ddl-auto=validate}를 통과시킨다.
 */
public enum SettlementItemStatus {

    /** 승인됨 · 구매확정 대기 — 결제 승인으로 적재됐으나 에스크로 릴리스(구매확정) 전이라 아직 정산 불가 */
    PENDING_CONFIRMATION,
    /** 에스크로 릴리스(구매확정) 완료 — 정산 배치가 집계할 수 있는 상태 */
    CONFIRMED,
    /** 정산 완료 — 해당 날짜 정산({@link Settlement})에 집계됨 */
    SETTLED,
    /** 구매확정 전 전액취소로 정산 제외 — 지급 대상에서 빠진다 */
    CANCELED
}
