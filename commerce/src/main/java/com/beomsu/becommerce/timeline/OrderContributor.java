package com.beomsu.becommerce.timeline;

import com.beomsu.becommerce.order.OrderTimelineFacts;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 주문 도메인의 사실을 타임라인 항목으로 옮긴다.
 *
 * <p>요약 문장을 여기서 만드는 이유는 ADR-011 트레이드오프 4에 있다 —
 * 도메인이 직접 만들게 하면 {@code order → timeline} 의존이 생겨 순환이 된다.
 * 그래서 도메인은 <b>무엇을 내줄지</b>만 정하고, <b>어떻게 요약할지</b>는 여기서 정한다.
 */
@Component
class OrderContributor implements TimelineContributor {

    private final OrderTimelineFacts facts;

    OrderContributor(OrderTimelineFacts facts) {
        this.facts = facts;
    }

    @Override
    public List<TimelineEntry> contribute(String orderNo) {
        return facts.findByOrderNo(orderNo)
                .map(o -> {
                    // 주문은 상태를 덮어쓰므로 중간 전이를 모른다. 생성과 마지막 변경 두 점만 찍는다.
                    // updatedAt이 createdAt과 같으면 아직 아무 일도 없었다는 뜻이라 한 줄만 낸다.
                    TimelineEntry created = TimelineEntry.of(
                            o.createdAt(), TimelineEntry.Source.ORDER, "ORDER_CREATED",
                            "주문 생성 — 항목 %d개".formatted(o.itemCount()), o.totalAmount());
                    if (o.updatedAt() == null || o.updatedAt().equals(o.createdAt())) {
                        return List.of(created);
                    }
                    return List.of(created, TimelineEntry.of(
                            o.updatedAt(), TimelineEntry.Source.ORDER, "ORDER_STATUS",
                            "주문 상태 %s".formatted(o.status())));
                })
                .orElseGet(List::of);   // 주문이 없는 것도 사실이다(대사의 EXTERNAL_ONLY)
    }

    @Override
    public TimelineEntry.Source source() {
        return TimelineEntry.Source.ORDER;
    }
}
