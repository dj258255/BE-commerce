package com.beomsu.becommerce.order.recovery;

import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderStatus;
import com.beomsu.becommerce.payment.PaymentRecoveredEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * 결제 복구가 확정한 결과로 멈춘 주문을 바로 마무리한다(#378).
 *
 * <p>예전에는 주문 복구 배치가 10분 넘게 멈춘 주문을 볼 때까지 기다렸다. 그 사이 한정 상품의 재고가 팔려 승인된
 * 결제가 망취소되거나(CHECK), 잡힌 재고가 판매 창이 끝난 뒤에야 풀렸다(AT_PAYMENT).
 *
 * <p>주문이 아직 {@code PAYMENT_IN_PROGRESS} 일 때만 움직인다. 결제는 이미 확정돼 있어 마무리 경로가 PG 에 다시
 * 묻지 않는다. 배치 · 고객의 다시 시도와 같은 주문을 동시에 마무리해도 주문의 {@code @Version} 이 하나만 통과시킨다.
 * {@code @ApplicationModuleListener} 라 커밋 뒤에 따로 돌고, 실패하면 이벤트 발행 기록에 남아 다시 시도된다.
 */
@Component
class PaymentRecoveredListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRecoveredListener.class);

    private final OrderRepository orderRepository;
    private final CheckoutRecoveryService checkoutRecoveryService;

    PaymentRecoveredListener(OrderRepository orderRepository, CheckoutRecoveryService checkoutRecoveryService) {
        this.orderRepository = orderRepository;
        this.checkoutRecoveryService = checkoutRecoveryService;
    }

    @ApplicationModuleListener
    void on(PaymentRecoveredEvent event) {
        orderRepository.findByOrderNo(event.orderNo())
                .filter(order -> order.getStatus() == OrderStatus.PAYMENT_IN_PROGRESS)
                .ifPresent(order -> {
                    checkoutRecoveryService.resolveNow(order);
                    log.info("결제 복구 확정({})으로 멈춘 주문 마무리 orderNo={}", event.status(), event.orderNo());
                });
    }
}
