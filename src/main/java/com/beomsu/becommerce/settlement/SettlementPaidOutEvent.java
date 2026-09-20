package com.beomsu.becommerce.settlement;

/**
 * 정산 지급 확정 이벤트. ledger 모듈이 구독해 <b>PG 미수금을 회수 처리</b>한다.
 *
 * <p><b>왜 필요한가</b> — 원장에 승인(미수금 증가)과 취소(미수금 감소)는 있는데 <b>PG로부터 실제로
 * 돈이 들어오는 사건이 없었다.</b> 그래서 PG 미수금 계정은 쌓이기만 하고 줄지 않아, 그 잔액이
 * 실제 미수금과 영원히 일치하지 않았다. "들어온 돈과 나간 돈이 맞는지 언제든 계산으로 확인할 수
 * 있게"라는 약속이 계정 잔액 차원에서는 성립하지 않는 상태였다.
 *
 * <p>이 이벤트가 들어오면 <b>미수금 = 승인합 − 취소합 − 입금합</b>이라는 검증식이 성립한다.
 *
 * <p>settlement는 ledger에 의존하지 않는다. 발행만 하고 소비는 ledger가 한다.
 * {@code settlementId}가 원장 멱등키의 {@code sourceId}로 쓰여 같은 지급이 두 번 분개되지 않는다.
 *
 * @param grossAmount 그 정산일의 총 결제액 — 회수되는 미수금
 * @param feeTotal    PG 수수료 + 그 부가세 — 우리가 부담하는 비용
 * @param netAmount   실제로 입금되는 금액 (gross − feeTotal)
 */
public record SettlementPaidOutEvent(Long settlementId, long grossAmount,
                                     long feeTotal, long netAmount) {
}
