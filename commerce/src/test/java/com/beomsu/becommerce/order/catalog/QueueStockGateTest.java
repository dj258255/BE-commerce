package com.beomsu.becommerce.order.catalog;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대기열 매진 판정(#385) — 게이트 상품 전부의 재고가 0이고 RESERVED 예약도 없을 때만 매진이다.
 */
@ExtendWith(MockitoExtension.class)
class QueueStockGateTest {

    private static final long PRODUCT_ID = 90374L;
    private static final String EVENT = "drop";

    @Mock
    StockRepository stockRepository;
    @Mock
    StockReservationRepository reservationRepository;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    private QueueStockGate gate() {
        return new QueueStockGate(stockRepository, reservationRepository, meterRegistry, List.of(PRODUCT_ID), EVENT);
    }

    @Test
    @DisplayName("재고가 남아 있으면 예약 여부와 무관하게 매진이 아니다")
    void notSoldOutWhileStockRemains() {
        when(stockRepository.findByProductIdIn(List.of(PRODUCT_ID)))
                .thenReturn(List.of(Stock.of(PRODUCT_ID, 3)));

        assertThat(gate().isSoldOut(EVENT)).isFalse();
        verify(reservationRepository, never()).existsByProductIdInAndStatus(anyCollection(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("재고 0 + 결과를 기다리는 RESERVED 예약이 있으면 아직 매진이 아니다(풀리면 돌아올 수 있다)")
    void notSoldOutWhileReservationHeld() {
        when(stockRepository.findByProductIdIn(List.of(PRODUCT_ID)))
                .thenReturn(List.of(Stock.of(PRODUCT_ID, 0)));
        when(reservationRepository.existsByProductIdInAndStatus(List.of(PRODUCT_ID), StockReservation.Status.RESERVED))
                .thenReturn(true);

        assertThat(gate().isSoldOut(EVENT)).isFalse();
        // "재고 0인데 못 닫았다"와 "재고가 애초에 0이 아니었다"를 구분하는 증거 카운터
        assertThat(meterRegistry.get("queue.sold_out_gate.depleted_but_held").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("재고 0 + 잡힌 예약 없음이면 매진이다")
    void soldOutWhenDepletedAndNoHolds() {
        when(stockRepository.findByProductIdIn(List.of(PRODUCT_ID)))
                .thenReturn(List.of(Stock.of(PRODUCT_ID, 0)));
        when(reservationRepository.existsByProductIdInAndStatus(List.of(PRODUCT_ID), StockReservation.Status.RESERVED))
                .thenReturn(false);

        assertThat(gate().isSoldOut(EVENT)).isTrue();
        assertThat(meterRegistry.get("queue.sold_out_gate.closed").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("게이트 상품이 없으면 항상 false — DB 질의 자체를 안 한다")
    void neverSoldOutWithoutGateProducts() {
        QueueStockGate noGate = new QueueStockGate(stockRepository, reservationRepository, meterRegistry, List.of(), EVENT);

        assertThat(noGate.isSoldOut(EVENT)).isFalse();
        verify(stockRepository, never()).findByProductIdIn(anyCollection());
    }

    @Test
    @DisplayName("게이트 이벤트가 아니면 항상 false")
    void neverSoldOutForOtherEvent() {
        assertThat(gate().isSoldOut("other-event")).isFalse();
        verify(stockRepository, never()).findByProductIdIn(anyCollection());
    }
}
