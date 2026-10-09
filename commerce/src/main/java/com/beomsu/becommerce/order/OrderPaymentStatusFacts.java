package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.internal.OrderRepository;
import com.beomsu.becommerce.order.internal.OrderStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문의 결제 결과를 다른 모듈(live)에 내주는 공개 진입점(R13, ADR-085) — {@link ProductCatalogFacts}
 * 와 같은 이유(order.internal을 직접 참조하면 ModularityTests가 경계 침투로 잡는다), 이번엔
 * 읽기다. {@link OrderStatus}는 order 내부 상태머신이라 그대로 내보내지 않고, 호출하는 쪽이
 * 실제로 필요한 세 갈래(확정됨·결과 모름·그 외)로 미리 좁혀 내준다 — {@code
 * PaymentRecoveredListener}가 이미 쓰는 것과 같은 상태 읽기이며, 새 복구 로직을 추가하지 않는다.
 */
@Service
public class OrderPaymentStatusFacts {

    private final OrderRepository orderRepository;

    OrderPaymentStatusFacts(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    /**
     * @return 주문이 {@code PAID}면 {@link Outcome#PAID}, {@code PAYMENT_IN_PROGRESS}(결제
     *         결과 UNKNOWN, 기존 결제 복구 흐름이 아직 확정하지 못한 상태)면
     *         {@link Outcome#IN_PROGRESS}, 그 외(미결제·실패·취소·만료·주문 없음)는
     *         {@link Outcome#OTHER}
     */
    @Transactional(readOnly = true)
    public Outcome outcomeOf(String orderNo) {
        return orderRepository.findByOrderNo(orderNo)
                .map(order -> switch (order.getStatus()) {
                    case PAID -> Outcome.PAID;
                    case PAYMENT_IN_PROGRESS -> Outcome.IN_PROGRESS;
                    default -> Outcome.OTHER;
                })
                .orElse(Outcome.OTHER);
    }

    public enum Outcome {
        PAID, IN_PROGRESS, OTHER
    }
}
