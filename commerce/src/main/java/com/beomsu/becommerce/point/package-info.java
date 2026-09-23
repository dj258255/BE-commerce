/**
 * 포인트(point) 모듈 — 복합결제(포인트+카드)의 내부 자금 수단.
 *
 * <p>포인트 잔액 차감/복원/환불을 담당한다. 카드 승인이 외부 PG에 의존하는 것과 달리 포인트는
 * 내부 DB 트랜잭션이라 롤백이 확실하므로, 복합결제 Saga에서 <b>먼저 선점되고 카드 실패 시 복원</b>되는
 * 보상 대상이다. 모든 잔액 변경은 {@code PointHistory}(append-only)에 이력을 남겨 멱등성과 추적성을 보장한다.
 */
@org.springframework.modulith.ApplicationModule(
        allowedDependencies = { "shared" }
)
package com.beomsu.becommerce.point;

import com.beomsu.becommerce.point.internal.PointHistory;
