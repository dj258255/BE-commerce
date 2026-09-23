package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.catalog.Stock;
import com.beomsu.becommerce.order.catalog.StockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 실제 재고 읽기 포트의 <b>계약</b>을 고정한다 — 무엇을 "팔 수 없다"로 보는가.
 *
 * <p>이 계약이 화면용 {@code CatalogQueryService.idsInStock} 과 <b>반대</b>라는 것이 요점이다.
 * 그쪽은 "재고 행이 없으면 있는 것으로" 보지만(fail-open), 제약 확인은 <b>"모르면 없는 것으로"</b>
 * 본다(fail-closed). 방향을 헷갈리면 확인이 조용히 무력해진다 — 모르는 것을 통과시키면 위반율이
 * 낮게 나오는데, 그 낮음이 <b>확인이 잘 해서인지 확인이 안 걸려서인지</b> 구분되지 않는다.
 */
class StockAvailabilityFactsTest {

    private final StockRepository stockRepository = mock(StockRepository.class);
    private final StockAvailabilityFacts facts = new StockAvailabilityFacts(stockRepository);

    @Test
    @DisplayName("재고가 남아 있으면 팔 수 있다 — unavailable 에 없다")
    void positiveQuantityIsAvailable() {
        when(stockRepository.findByProductIdIn(List.of(1L))).thenReturn(List.of(Stock.of(1L, 5)));

        assertThat(facts.unavailableAmong(List.of(1L))).isEmpty();
    }

    @Test
    @DisplayName("재고가 0이면 팔 수 없다")
    void zeroQuantityIsUnavailable() {
        when(stockRepository.findByProductIdIn(List.of(2L))).thenReturn(List.of(Stock.of(2L, 0)));

        assertThat(facts.unavailableAmong(List.of(2L))).containsExactly(2L);
    }

    @Test
    @DisplayName("재고 행이 없으면 팔 수 없다 — 모르면 통과가 아니라 거절이다(화면용 규칙과 반대)")
    void missingRowIsUnavailable() {
        when(stockRepository.findByProductIdIn(List.of(3L))).thenReturn(List.of());

        assertThat(facts.unavailableAmong(List.of(3L)))
                .as("이 방향이 뒤집히면 제약 확인이 조용히 무력해진다")
                .containsExactly(3L);
    }

    @Test
    @DisplayName("섞여 있으면 팔 수 없는 것만 골라낸다(한 번의 질의로)")
    void picksOnlyUnavailableOnes() {
        when(stockRepository.findByProductIdIn(List.of(1L, 2L, 3L)))
                .thenReturn(List.of(Stock.of(1L, 5), Stock.of(2L, 0)));

        assertThat(facts.unavailableAmong(List.of(1L, 2L, 3L))).containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    @DisplayName("빈 입력은 빈 결과 — 질의를 만들지 않는다")
    void emptyInputNeedsNoQuery() {
        assertThat(facts.unavailableAmong(List.of())).isEmpty();
    }
}
