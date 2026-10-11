package com.beomsu.becommerce.live;

import com.beomsu.becommerce.order.OrderPaymentStatusFacts;
import org.springframework.stereotype.Component;

/**
 * {@link OrderPaymentStatus}(media가 정의한 포트)의 commerce 쪽 구현(R13, R32·ADR-085) —
 * {@code LiveProductLookupAdapter}와 같은 자리(split package). order가 공개한 읽기 전용
 * 진입점 하나({@link OrderPaymentStatusFacts})를 그대로 감싼다 — order는 이 클래스나
 * media(live)를 전혀 모른다.
 */
@Component
class LiveOrderPaymentStatusAdapter implements OrderPaymentStatus {

    private final OrderPaymentStatusFacts orderPaymentStatusFacts;

    LiveOrderPaymentStatusAdapter(OrderPaymentStatusFacts orderPaymentStatusFacts) {
        this.orderPaymentStatusFacts = orderPaymentStatusFacts;
    }

    @Override
    public Outcome outcomeOf(String orderNo) {
        return switch (orderPaymentStatusFacts.outcomeOf(orderNo)) {
            case PAID -> Outcome.PAID;
            case IN_PROGRESS -> Outcome.IN_PROGRESS;
            case OTHER -> Outcome.OTHER;
        };
    }
}
