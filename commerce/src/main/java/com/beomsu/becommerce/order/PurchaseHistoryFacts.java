package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.internal.OrderRepository;
import java.util.List;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 주문 도메인이 추천에 내주는 사실: 사용자가 <b>산</b> 상품(#254).
 *
 * <p>GenPage 모델은 구매 시퀀스로 학습했다. 서빙이 조회·클릭을 넣으면 학습과 입력이 달라 오프라인 점수가 서빙 품질을 말해 주지 못한다
 * (ADR-053). 그래서 구매를 여기서 내준다. 결제 완료(PAID) 주문만 센다 — 결제 전·실패·취소는 산 것이 아니다.
 */
@Service
public class PurchaseHistoryFacts {

    private final OrderRepository orders;

    PurchaseHistoryFacts(OrderRepository orders) {
        this.orders = orders;
    }

    /** 최근 산 상품 id, 최근 것부터. 같은 상품을 여러 번 샀으면 그만큼 나온다(시퀀스 그대로). */
    @Transactional(readOnly = true)
    public List<Long> recentPurchasedProductIds(long userId, int limit) {
        return orders.findPurchasedProductIds(userId, PageRequest.of(0, Math.max(limit, 1)));
    }
}
