package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 재고를 언제 잡을지(#374). 전략은 {@code app.stock.reservation} 이고 기본값은 {@link Strategy#CHECK} 다.
 *
 * <p>기본값이 #374 에서 {@code NONE} 에서 {@code CHECK} 로 바뀌었다. ADR-003 은 "주문 생성 → (재고 여유 확인만) →
 * 승인 → 차감"을 적었는데 코드에는 여유 확인이 없었다. 한정 상품 부하(재고 100, 주문 300)에서 망취소가
 * NONE 116건, CHECK 9건이었다. 판정 기준(3배 초과면 기본으로)을 측정 전에 이슈에 적었다.
 *
 * <ul>
 *   <li>{@code NONE}: 확인 없이 승인 뒤 차감. 매진 뒤 결제도 PG 승인까지 가고 망취소된다</li>
 *   <li>{@code CHECK}: ADR-003 이 적은 대로 주문 생성 때 여유만 확인한다(잡지 않는다). 차감은 승인 뒤</li>
 *   <li>{@code AT_PAYMENT}: 결제 시작(확정 요청의 예약 단계, PG 호출 전)에 잡는다. Shopify 의 reserve → claim 과 같은 자리</li>
 *   <li>{@code AT_ORDER}: 주문 생성 때 잡는다. ADR-003 이 버린 안(이탈한 주문이 만료까지 재고를 문다)</li>
 * </ul>
 *
 * <p>잡을 때 재고는 조건부 UPDATE 로 바로 뺀다. 예약 행은 그 사실과 끝을 남기고, 승인이면 확정(재고는 그대로),
 * 거절 · 만료면 되돌린다(재고를 더한다). 결과를 모르는 동안은 잡은 채 둔다.
 */
@Service
public class StockReservationService {

    public enum Strategy { NONE, CHECK, AT_PAYMENT, AT_ORDER }

    /** 잡을 한 줄. 주문 항목에서 만든다. */
    public record Line(long productId, int quantity) {
    }

    private final StockReservationRepository reservations;
    private final StockRepository stockRepository;
    private final MeterRegistry meterRegistry;
    private final Strategy strategy;
    private final Clock clock;

    public StockReservationService(StockReservationRepository reservations, StockRepository stockRepository,
                                   MeterRegistry meterRegistry,
                                   @Value("${app.stock.reservation:CHECK}") Strategy strategy) {
        this.reservations = reservations;
        this.stockRepository = stockRepository;
        this.meterRegistry = meterRegistry;
        this.strategy = strategy;
        this.clock = Clock.systemUTC();
    }

    public Strategy strategy() {
        return strategy;
    }

    /** {@code CHECK}: 지금 재고가 모자라면 주문을 만들지 않는다. 잡지는 않으므로 결제 사이에 팔릴 수 있다. */
    @Transactional(readOnly = true)
    public void checkAvailable(List<Line> lines) {
        Map<Long, Integer> quantities = stockRepository.findByProductIdIn(
                        lines.stream().map(Line::productId).toList()).stream()
                .collect(Collectors.toMap(Stock::getProductId, Stock::getQuantity));
        for (Line line : lines) {
            if (quantities.getOrDefault(line.productId(), 0) < line.quantity()) {
                meterRegistry.counter("stock.reservation.out_of_stock", "at", "check").increment();
                throw OrderException.outOfStock(line.productId());
            }
        }
    }

    /**
     * 잡는다. 상품 id 오름차순으로 조건부 UPDATE 를 해 잠금 순서를 모든 트랜잭션에서 같게 한다(데드락 방지).
     * 하나라도 모자라면 {@code OUT_OF_STOCK} 을 던진다. 호출한 쪽의 트랜잭션에 합류하므로 앞에서 뺀 재고와
     * 주문 · 결제 쪽 변경이 함께 롤백된다.
     *
     * <p>같은 주문을 다시 부르면(거절 뒤 다시 시도) 이미 잡힌 줄은 건너뛰고 되돌린 줄만 다시 잡는다.
     */
    @Transactional
    public void reserve(String orderNo, List<Line> lines, String at) {
        Map<Long, StockReservation> existing = reservations.findByOrderNo(orderNo).stream()
                .collect(Collectors.toMap(StockReservation::getProductId, Function.identity()));
        Instant now = clock.instant();
        List<Line> ordered = lines.stream().sorted(Comparator.comparingLong(Line::productId)).toList();
        for (Line line : ordered) {
            StockReservation current = existing.get(line.productId());
            if (current != null && current.getStatus() != StockReservation.Status.RELEASED) {
                continue;   // 이미 잡혀 있거나 확정됐다
            }
            if (stockRepository.deductConditionally(line.productId(), line.quantity()) == 0) {
                meterRegistry.counter("stock.reservation.out_of_stock", "at", at).increment();
                throw OrderException.outOfStock(line.productId());
            }
            if (current == null) {
                reservations.save(StockReservation.reserve(orderNo, line.productId(), line.quantity(), now));
            } else if (reservations.reReserve(current.getId(), now) == 0) {
                // 다른 요청이 먼저 다시 잡았다. 방금 뺀 재고를 되돌린다
                stockRepository.restore(line.productId(), line.quantity());
            }
        }
        meterRegistry.counter("stock.reservation.reserved", "at", at).increment();
    }

    /**
     * 승인: 이 주문의 예약을 확정한다. 재고는 예약 때 이미 빠져 있다.
     *
     * @return 예약이 있어 확정으로 처리했으면 true. 없거나 모두 되돌려졌으면 false(호출자가 승인 뒤 차감으로 간다)
     */
    @Transactional
    public boolean claim(String orderNo) {
        List<StockReservation> rows = reservations.findByOrderNo(orderNo);
        boolean held = rows.stream().anyMatch(r -> r.getStatus() != StockReservation.Status.RELEASED);
        if (!held) {
            return false;
        }
        int claimed = reservations.claimReserved(orderNo, clock.instant());
        if (claimed > 0) {
            meterRegistry.counter("stock.reservation.claimed").increment(claimed);
        }
        return true;   // 이미 CLAIMED 였으면(확정 단계를 다시 도는 복구) 0 행이어도 확정된 것이다
    }

    /** 되돌린다: RESERVED 인 줄만 재고에 더하고 RELEASED 로. 여러 번 불러도 한 번만 되돌린다. 되돌린 줄 수를 돌려준다. */
    @Transactional
    public int release(String orderNo, String reason) {
        int released = 0;
        Instant now = clock.instant();
        for (StockReservation r : reservations.findByOrderNo(orderNo)) {
            if (r.getStatus() == StockReservation.Status.RESERVED && reservations.markReleased(r.getId(), now) == 1) {
                stockRepository.restore(r.getProductId(), r.getQuantity());
                released++;
            }
        }
        if (released > 0) {
            meterRegistry.counter("stock.reservation.released", "reason", reason).increment(released);
        }
        return released;
    }
}
