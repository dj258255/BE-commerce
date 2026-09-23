package com.beomsu.becommerce.order;

import com.beomsu.becommerce.order.catalog.Stock;
import com.beomsu.becommerce.order.catalog.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 재고가 <b>다른 모듈에 내주는 가용성 사실</b> (ADR-018).
 *
 * <p><b>왜 이 클래스가 있나</b>: 재고({@link Stock}·{@code StockRepository})는 {@code order.catalog}
 * 안에 있고 그 패키지는 order 모듈의 <b>내부</b>다. 다른 모듈이 직접 import하면
 * {@code ModularityTests}가 경계 침투로 잡는다. 그래서 order 도메인이 <b>무엇을 남에게 보여줄지
 * 스스로 정해</b> 읽기 전용으로만 노출한다 — {@link ProductCatalogFacts}와 같은 이유, 같은 방식이다.
 *
 * <p><b>왜 {@code CatalogQueryService.idsInStock}을 재사용하지 않는가</b>: 그쪽은 <b>화면용</b>이라
 * "재고 행이 없으면 있는 것으로 본다"(fail-open) — 시드가 없는 상품을 품절로 보이게 하지 않으려는
 * 것이다. 이 포트는 <b>제약 확인용</b>이라 규칙이 <b>반대</b>다: <b>모르면 없는 것으로 본다</b>.
 * 재고 행이 없다는 것은 "아직 모른다"가 아니라 "팔 수 있는 근거가 없다"이고, 모르는 것을 통과시키면
 * 제약 확인이 조용히 무력해진다. 두 규칙은 용도가 달라 하나로 합칠 수 없다 — 그래서 포트를 나눴다.
 *
 * <p><b>읽기 표면이 좁은 이유</b>: 남에게 필요한 것은 "이 중 지금 팔 수 없는 것" 하나다. 상품명·가격
 * 같은 것은 {@link ProductCatalogFacts}가 따로 내준다 — 필요 이상을 내주면 계약이 이유 없이 넓어진다.
 */
@Service
public class StockAvailabilityFacts {

    private final StockRepository stockRepository;

    public StockAvailabilityFacts(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    /**
     * 주어진 id 중 <b>지금 팔 수 없는 것</b>만 골라 돌려준다.
     *
     * <p>팔 수 없는 것은 둘이다: ① 재고 행은 있는데 {@code quantity ≤ 0} ② <b>재고 행이 아예 없다</b>
     * (모르면 거절 — 위 javadoc 참고). 빈 입력은 빈 결과다(질의를 만들지 않는다).
     *
     * <p>읽기는 <b>한 번</b>이다 — id 묶음으로 {@code IN} 질의를 한 번 날려 N+1을 피한다. 이 호출이
     * 제약 확인의 비용이고(E4 의 비용 축), 호출 횟수가 곧 지연이므로 질의도 한 번이어야 한다.
     */
    @Transactional(readOnly = true)
    public Set<Long> unavailableAmong(Collection<Long> itemIds) {
        if (itemIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, Integer> quantities = stockRepository.findByProductIdIn(itemIds).stream()
                .collect(Collectors.toMap(Stock::getProductId, Stock::getQuantity, (a, b) -> a));
        Set<Long> unavailable = new HashSet<>();
        for (Long itemId : itemIds) {
            Integer quantity = quantities.get(itemId);
            if (quantity == null || quantity <= 0) {
                unavailable.add(itemId);
            }
        }
        return unavailable;
    }
}
