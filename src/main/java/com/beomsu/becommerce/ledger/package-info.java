/**
 * 원장(ledger) 모듈 — 복식부기(double-entry).
 *
 * <p>payment의 결제 완료·취소 이벤트, dispute의 패소 이벤트를 구독해 분개(journal entry)를
 * append-only로 기록한다. 모든 거래는 차변 합계 = 대변 합계를 만족해야 하며, 이 불변식이 자금
 * 이동의 정합성을 수학적으로 보장한다. 잔액은 엔트리의 합으로 파생된다.
 *
 * <p>정산 지급 확정({@link com.beomsu.becommerce.settlement.SettlementPaidOutEvent})을 구독해 PG 미수금을
 * 회수 처리한다. 이 분개가 있어야 미수금 = 승인합 - 취소합 - 입금합이 성립한다.
 *
 * <p>dispute 패소 역분개를 위해 {@code dispute}에 의존한다({@link com.beomsu.becommerce.dispute.DisputeLostEvent}
 * 참조). dispute는 ledger를 모르므로(이벤트만 발행) 순환은 없다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "dispute", "settlement" }
)
package com.beomsu.becommerce.ledger;
