/**
 * 정산(settlement) 모듈.
 *
 * <p>서비스 루프 기반 일 단위 거래 집계 → 수수료(bps)·부가세 계산 → 지급예정일(영업일) 산출 →
 * 가맹점 지급금 생성(대용량은 Spring Batch로 확장 여지). 배치 재실행 멱등성(settlement_date 유니크)을
 * 보장한다. 수수료와 그 부가세는 정산 지급 확정 시 원장 비용 계정(PG_FEE)으로 분개된다
 * (ledger 모듈이 SettlementPaidOutEvent 를 구독해 처리한다).
 *
 * <p><b>에스크로 정렬</b>: 정산은 payment(승인·취소)뿐 아니라 escrow의 {@code EscrowReleasedEvent}
 * (구매확정)를 구독해 정산 가능 시점을 에스크로 릴리스에 맞춘다. 의존은 settlement→escrow 단방향이며
 * (escrow는 settlement를 모른다), escrow가 {shared, payment}만 의존하므로 순환이 없다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "escrow", "seller" }
)
package com.beomsu.becommerce.settlement;

import com.beomsu.becommerce.escrow.EscrowReleasedEvent;
