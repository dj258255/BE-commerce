package com.beomsu.becommerce.recommendation.internal;

import com.beomsu.becommerce.order.ProductCatalogFacts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 추천이 <b>무엇을 추천할 수 있는가</b> — 후보 집합의 원천.
 *
 * <p><b>왜 갈래가 둘인가(M7의 "제품 연결")</b>: 홈은 상품 카드(이름·가격·이미지)를 그려야 하고,
 * 그러려면 추천이 내놓는 id가 <b>커머스 카탈로그에 있어야</b> 한다. 그런데 실험 하네스(E4·E5)는
 * 반대를 요구한다 — 변화 주입(churn)이 <b>실제 재고를 건드리면 안 된다</b>(ADR-039의 격리).
 *
 * <p>그래서 원천을 갈래로 나누고, <b>기본을 카탈로그</b>로 둔다:
 * <ul>
 *   <li>{@link Mode#CATALOG}(기본) — 실제 상품 id. 홈이 카드를 그릴 수 있고, 제약 확인이 실제
 *       재고를 읽는다(E4에서 이미 그렇게 바꿨다)</li>
 *   <li>{@link Mode#EXPERIMENT} — 실험용 합성 풀({@link ItemPool}). <b>하네스가 명시적으로 고른다</b> —
 *       격리는 이제 "기본값"이 아니라 "하네스가 풀을 고르는 것"으로 지켜진다</li>
 * </ul>
 *
 * <p><b>대가</b>: 갈래가 둘이라는 것 자체가 비용이다(경로마다 다른 id 공간 → 실험이 재는 것과
 * 홈이 그리는 것이 다를 수 있다). 그래서 <b>기본을 실제 카탈로그로</b> 두었다 — 갈래는 실험을 위한
 * 예외이고, 예외는 명시적으로만 켜진다. 판단과 대가는 ADR-042.
 *
 * <p><b>캐시</b>: 상품은 시드·마이그레이션으로만 늘어나므로(카탈로그 조회 서비스의 전제) 한 번 읽고
 * 들고 있는다. 요청마다 전체 상품을 훑으면 후보 집합 조회가 곧 비용이 된다.
 */
@Component
public class ItemPoolSource {

    private static final Logger log = LoggerFactory.getLogger(ItemPoolSource.class);

    public enum Mode {
        CATALOG,
        EXPERIMENT
    }

    private final Mode mode;
    private final ProductCatalogFacts catalog;
    private final int popularSize;

    private volatile List<Long> cached;

    public ItemPoolSource(@Value("${app.recommendation.item-pool:CATALOG}") Mode mode,
                          @Value("${app.recommendation.result-size:12}") int popularSize,
                          ProductCatalogFacts catalog) {
        this.mode = mode;
        this.popularSize = Math.max(popularSize, 1);
        this.catalog = catalog;
        log.info("추천 후보 집합={} (CATALOG=실제 상품 · EXPERIMENT=실험용 합성 풀)", mode);
    }

    public Mode mode() {
        return mode;
    }

    /** 확인 대상 전체 — 제약 확인의 스냅샷과 모델의 채움 목록이 이걸 본다. */
    public List<Long> pool() {
        if (mode == Mode.EXPERIMENT) {
            return ItemPool.experimentPool();
        }
        return catalogIds();
    }

    /**
     * 모델이 "인기 상품"으로 채울 때 쓰는 id — <b>후보 집합에 고르게 퍼뜨려 뽑는다.</b>
     *
     * <p><b>왜 앞에서 자르지 않는가(M7 실측)</b>: {@code subList(0, 12)} 로 앞을 잘랐더니 그 12개가
     * <b>전부 한 대분류</b>였다 — H&M 상품 id 가 카테고리별로 뭉쳐 있어서 id 순서는 곧 카테고리 순서다.
     * 그 결과 홈이 <b>한 카테고리짜리 화면</b>이 되었고(실측 distinct 대분류 = 1), 다양성 규칙이
     * 그 뒤에서 조용히 항목을 버렸다. **인기 신호가 없는 것을 id 순서로 대신하면 안 된다.**
     *
     * <p>그래서 지금은 <b>결정적 간격(stride)으로 퍼뜨린다</b> — 인기를 흉내 내는 것이 아니라
     * <b>카테고리 편향을 만들지 않는 것</b>이 목적이다. 진짜 인기 통계(M1의 인기 상품)가 들어오면
     * 이 메서드가 그것을 읽는다. 그때까지 이 행의 이름은 "인기"가 아니라 <b>"후보 집합"</b>에 가깝다.
     */
    public List<Long> popular() {
        if (mode == Mode.EXPERIMENT) {
            return ItemPool.POPULAR;
        }
        List<Long> all = catalogIds();
        int want = Math.min(popularSize, all.size());
        if (want == 0) {
            return List.of();
        }
        List<Long> spread = new java.util.ArrayList<>(want);
        for (int i = 0; i < want; i++) {
            spread.add(all.get((int) ((long) i * all.size() / want)));
        }
        return List.copyOf(spread);
    }

    private List<Long> catalogIds() {
        List<Long> ids = cached;
        if (ids == null) {
            synchronized (this) {
                if (cached == null) {
                    cached = List.copyOf(catalog.allProductIds());
                    log.info("카탈로그 후보 집합을 읽었다 — {}개", cached.size());
                }
                ids = cached;
            }
        }
        return ids;
    }
}
