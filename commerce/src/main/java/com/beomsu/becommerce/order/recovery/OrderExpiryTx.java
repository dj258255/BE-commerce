package com.beomsu.becommerce.order.recovery;

import com.beomsu.becommerce.order.catalog.StockReservationService;
import com.beomsu.becommerce.order.internal.Order;
import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 만료 한 건의 <b>트랜잭션 경계</b> — 배치가 건별로 프록시를 경유하게 하려고 별도 빈으로 둔다.
 *
 * <p>{@link OrderExpiryService#expireOverdue} 가 클래스 레벨 {@code @Transactional} 이면, 루프 안에서
 * 예외를 잡아도 그 예외가 트랜잭션 참여 빈({@code saveAndFlush}·{@code release})에서 나온 것이면 공유
 * 트랜잭션이 rollback-only 로 오염돼 <b>성공한 건까지 함께 롤백</b>된다(실 MySQL 통합 테스트로 재현 —
 * {@code OrderExpiryRollbackIsolationMySqlTest}). 건별 경계로 나눠 한 건의 실패가 다른 건을 되돌리지
 * 않게 한다({@code CompensationExecutor}·{@code EscrowReleaseTx} 와 같은 분리 빈 패턴).
 */
@Component
@RequiredArgsConstructor
class OrderExpiryTx {

    private final OrderRepository orderRepository;
    private final StockReservationService stockReservationService;

    /**
     * 만료 대상 한 건을 EXPIRED 로 전이하고 잡아 둔 재고를 되돌린다. 자기 트랜잭션 안에서 돈다.
     *
     * <p>스캔 뒤 그 사이 다른 경로가 상태를 바꿨으면(예: 결제 완료) 아무 것도 하지 않는다.
     */
    @Transactional
    public void expire(Order order) {
        if (order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            return;
        }
        order.markExpired();
        // 상태 전이(EXPIRED)를 saveAndFlush로 명시 영속한다. dirty-check 자동 flush는 readOnly
        // 조회로 세션 FlushMode가 MANUAL이거나 detached 엔티티인 경우 신뢰할 수 없어(pay-26 교훈) 확정을 강제한다.
        orderRepository.saveAndFlush(order);
        // 잡아 둔 재고가 있으면 되돌린다(#374, 주로 AT_ORDER 의 이탈 주문). 없으면 아무것도 안 한다.
        stockReservationService.release(order.getOrderNo(), "order_expired");
    }
}
