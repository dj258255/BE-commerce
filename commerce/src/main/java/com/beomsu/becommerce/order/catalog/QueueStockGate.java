package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.queue.QueueSoldOutGate;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * {@link QueueSoldOutGate} 구현(#385) — 게이트 상품의 재고와 예약을 보고 대기열에 매진을 알린다.
 *
 * <p>매진 = 지정된 게이트 상품 전부의 재고가 0이고, 그중 어느 것도 RESERVED(결과를 기다리는 중이라 재고가
 * 돌아올 수도 있는) 예약이 없다. 게이트 상품이 없으면(기본) 또는 묻는 eventId가 게이트 이벤트가
 * 아니면 항상 false다 — 이 클래스가 있어도 게이트를 쓰지 않는 배포에는 DB 질의가 전혀 없다.
 *
 * <p>재고가 0이어도 결과를 기다리는 예약이 남아 있는 동안은 매진이 아니다({@code depleted_but_held}로
 * 센다) — 측정에서 매진 판정이 한 번도 안 서면(0) 이 카운터로 "재고는 0인데 예약이 안 풀려 못 닫았다"와
 * "애초에 재고가 0이 된 적이 없다"를 구분한다.
 */
@Component
class QueueStockGate implements QueueSoldOutGate {

    private final StockRepository stockRepository;
    private final StockReservationRepository reservationRepository;
    private final MeterRegistry meterRegistry;
    private final List<Long> gateProductIds;
    private final String gateEventId;

    QueueStockGate(StockRepository stockRepository,
                   StockReservationRepository reservationRepository,
                   MeterRegistry meterRegistry,
                   @Value("${app.queue.gate.product-ids:}") List<Long> gateProductIds,
                   @Value("${app.queue.gate.event-id:drop}") String gateEventId) {
        this.stockRepository = stockRepository;
        this.reservationRepository = reservationRepository;
        this.meterRegistry = meterRegistry;
        this.gateProductIds = gateProductIds;
        this.gateEventId = gateEventId;
    }

    @Override
    public boolean isSoldOut(String eventId) {
        if (gateProductIds.isEmpty() || !gateEventId.equals(eventId)) {
            return false;
        }
        meterRegistry.counter("queue.sold_out_gate.checked").increment();
        Map<Long, Integer> quantities = stockRepository.findByProductIdIn(gateProductIds).stream()
                .collect(Collectors.toMap(Stock::getProductId, Stock::getQuantity));
        boolean allDepleted = gateProductIds.stream().allMatch(id -> quantities.getOrDefault(id, 0) <= 0);
        if (!allDepleted) {
            return false;   // 아직 남았다 — 팔 게 있는데 문을 닫으면 안 된다
        }
        // 재고는 0이지만 결과를 기다리는 예약이 있으면(결과 모름 결제) 풀려서 돌아올 수 있다 — 아직 매진이 아니다
        boolean held = reservationRepository.existsByProductIdInAndStatus(
                Collections.unmodifiableList(gateProductIds), StockReservation.Status.RESERVED);
        if (held) {
            meterRegistry.counter("queue.sold_out_gate.depleted_but_held").increment();
            return false;
        }
        meterRegistry.counter("queue.sold_out_gate.closed").increment();
        return true;
    }
}
