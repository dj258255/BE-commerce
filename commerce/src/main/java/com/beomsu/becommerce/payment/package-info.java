/**
 * 결제(payment) 모듈 — 시스템의 심장.
 *
 * <p>승인/취소/부분취소, 결제 상태머신(READY → IN_PROGRESS → UNKNOWN → DONE → ...),
 * 멱등키, PG 연동(3-상태 모델), 망취소/보상, 웹훅 수신을 담당한다.
 * 결제 완료·취소 시 도메인 이벤트를 발행하고, ledger/settlement 등은 이를 구독한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.payment;
