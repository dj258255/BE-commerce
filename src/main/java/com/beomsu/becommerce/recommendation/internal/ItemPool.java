package com.beomsu.becommerce.recommendation.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * 실험이 쓰는 상품 id 풀 — <b>합성이다</b>.
 *
 * <p>모델 스텁이 만들 수 있는 id와 폴백 인기 상품, 그리고 부하 생성기가 활동으로 넣는 id가
 * <b>같은 풀</b>이어야 실험이 성립한다. 제약 확인이 걸러낼 대상이 응답에 실제로 들어 있어야
 * 위반율이 의미를 갖기 때문이다 — 풀이 갈라지면 위반율이 0으로 나오고, 그것은 "확인이 잘 해서"가
 * 아니라 "확인할 게 없어서"다.
 *
 * <p>그래서 세 곳(모델 스텁·폴백·제약 확인)이 같은 상수를 본다. 여기 모아 둔 이유가 그것이다.
 *
 * <p><b>이 id 들에는 실제 stock 행이 있어야 한다</b>(E4 후속). 제약 확인이
 * {@code order.StockAvailabilityFacts} 를 통해 실제 재고를 읽고, 재고 행이 없으면 fail-closed 로
 * 전부 걸러지기 때문이다. 그 행은 {@code V60__recommendation_experiment_stock.sql} 이 심고,
 * {@code ExperimentStockSeedIntegrationTest} 가 <b>이 상수와의 일치</b>를 고정한다 — SQL 과 Java 가
 * 갈라지면 추천이 조용히 비거나 위반율이 의미를 잃는다.
 *
 * <p>{@code web} 패키지(실험 계기)도 실험 풀을 알아야 해서 이 클래스는 public 이다 — 내부 패키지라
 * 모듈 API 는 아니고(모듈 밖 접근은 {@code allowedDependencies} 가 막는다), 모듈 안에서만 넓어진다.
 */
public final class ItemPool {

    /** 폴백 인기 상품이자 모델이 채우는 id. */
    static final List<Long> POPULAR = List.of(
            1_000_001L, 1_000_002L, 1_000_003L, 1_000_004L, 1_000_005L, 1_000_006L,
            1_000_007L, 1_000_008L, 1_000_009L, 1_000_010L, 1_000_011L, 1_000_012L);

    /** 부하 생성기가 활동으로 넣는 id — 모델이 "최근 본 것"으로 앞에 놓는다. */
    static final List<Long> RECENT = List.of(
            1_000_101L, 1_000_102L, 1_000_103L, 1_000_104L, 1_000_105L, 1_000_106L,
            1_000_107L, 1_000_108L, 1_000_109L, 1_000_110L, 1_000_111L, 1_000_112L);

    private ItemPool() {
    }

    /** 제약 확인이 훑는 전체 풀 — {@code AT_GENERATION_START}는 모델 출력을 모르므로 이걸 통째로 본다. */
    public static List<Long> experimentPool() {
        List<Long> all = new ArrayList<>(POPULAR.size() + RECENT.size());
        all.addAll(POPULAR);
        all.addAll(RECENT);
        return all;
    }
}
