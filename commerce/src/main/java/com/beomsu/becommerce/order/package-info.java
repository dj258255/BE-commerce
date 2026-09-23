/**
 * 주문(order) 모듈.
 *
 * <p>주문 생성, 주문 상태머신(CREATED → PENDING_PAYMENT → PAID → ...), 금액 위변조 검증의
 * 기준값이 되는 total_amount를 관리한다. 결제 결과는 payment 모듈이 발행하는 이벤트를
 * 구독해 반영하며, payment 모듈을 직접 호출하지 않는다.
 *
 * <p>복합결제(포인트+카드) 오케스트레이션을 위해 point 모듈에 의존한다 — 포인트 선점/복원은
 * order의 체크아웃 흐름이 조율하는 내부 Saga의 한 단계다.
 *
 * <p>구매확정(에스크로 릴리스)의 진입점도 order가 소유한다 — 릴리스는 구매자 본인만 할 수 있어야
 * 하고(IDOR 방지), 소유권 검증(userId ↔ 주문)의 기준값을 order가 갖기 때문이다. 소유권을 검증한
 * 뒤에만 escrow 모듈에 릴리스를 위임하므로 escrow에 의존한다(escrow는 order에 의존하지 않아 순환 없음).
 *
 * <p>게이트 상품(선착순 이벤트 대상)의 주문 생성 시 대기열 입장권을 서버가 강제 검증하기 위해
 * queue 모듈에 의존한다 — queue는 아무 모듈에도 의존하지 않으므로(allowedDependencies={}) 순환 없음.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared", "payment", "point", "escrow", "queue", "wallet" }
)
package com.beomsu.becommerce.order;
