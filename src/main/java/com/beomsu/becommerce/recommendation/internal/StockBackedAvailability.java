package com.beomsu.becommerce.recommendation.internal;

import com.beomsu.becommerce.order.ExperimentStockChurn;
import com.beomsu.becommerce.order.StockAvailabilityFacts;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * 제약의 원천을 <b>실제 재고</b>로 잇는 {@link AvailabilitySource} 구현 (E4 후속).
 *
 * <p><b>무엇이 바뀌었나</b>: 이전 구현({@code SyntheticAvailability})은 메모리 집합이라 확인이
 * <b>공짜</b>였다 — 네 정책 모두 비용이 0ms 로 재져 "확인 1회 vs 2회"의 교환비를 계산할 수 없었다
 * (E4 리포트 「틀렸던 것」 ②). 이제 확인은 {@link StockAvailabilityFacts} 를 지나 <b>DB 왕복</b>을
 * 한다. 그래서 확인 시점이 곧 <b>지연</b>이 되고, 교환비가 처음으로 숫자로 나온다.
 *
 * <p><b>모듈 경계</b>: 재고는 order 가 소유하므로 이 모듈은 order 루트 포트로만 접근한다
 * ({@code StockAvailabilityFacts} — ADR-018). 재고를 <b>바꾸는</b> 것도 order 가 한다
 * ({@code ExperimentStockChurn}) — 이 클래스는 읽기만 한다.
 *
 * <p><b>왜 {@code version()} 이 게이트된 빈에 의존하나</b>: "창 안에서 사실이 몇 번 바뀌었나"는
 * <b>변화를 주입하는 쪽만</b> 알 수 있다. 변화 주입기는 실험 밖에서 빈이 없다(게이트) — 그때는
 * 바뀐 것도 없으므로 0이 정직한 값이다. {@link ObjectProvider} 로 없음을 정상 상태로 다룬다.
 */
@Component
public class StockBackedAvailability implements AvailabilitySource {

    private final StockAvailabilityFacts stockFacts;
    private final ObjectProvider<ExperimentStockChurn> churn;

    public StockBackedAvailability(StockAvailabilityFacts stockFacts,
                                   ObjectProvider<ExperimentStockChurn> churn) {
        this.stockFacts = stockFacts;
        this.churn = churn;
    }

    /** 실제 재고를 읽는다 — 이 호출이 E4 의 비용 축이다(DB 왕복 한 번). */
    @Override
    public Set<Long> unavailableAmong(Collection<Long> itemIds) {
        return stockFacts.unavailableAmong(itemIds);
    }

    /**
     * 확인 대상 전체 — <b>실험의 후보 집합</b>이다.
     *
     * <p>실제 시스템이라면 "추천 후보로 고려하는 상품 집합"이 여기 온다. 이 저장소에서는 그 후보가
     * 실험 풀({@link ItemPool})이고, 모델 스텁과 폴백이 만드는 id 와 같은 집합이어야 제약 확인이
     * 걸러낼 대상이 응답에 실제로 들어 있다(풀이 갈라지면 위반율이 의미를 잃는다).
     */
    @Override
    public List<Long> knownItemIds() {
        return ItemPool.experimentPool();
    }

    /** 가용성이 바뀐 횟수 — 변화 주입기(게이트)가 없으면 0이다(바뀐 것이 없다는 뜻). */
    @Override
    public long version() {
        ExperimentStockChurn active = churn.getIfAvailable();
        return active == null ? 0L : active.version();
    }
}
