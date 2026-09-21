package com.beomsu.becommerce.recommendation.internal;

import com.beomsu.becommerce.order.ProductCatalogFacts;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
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

    /**
     * "인기"를 어디서 얻는가(#198②) — 측정 대조군을 남기기 위한 축이다.
     *
     * <ul>
     *   <li>{@link #AUTO}(기본) — 인기 통계(M1 배치 적재분)를 읽고, <b>없으면</b> 후보 집합에 퍼뜨린다</li>
     *   <li>{@link #STRIDE} — 퍼뜨리기만 쓴다. <b>인기 신호를 켜기 전의 동작</b>이라 A/B 의 대조군이다</li>
     * </ul>
     */
    public enum PopularitySource {
        AUTO,
        STRIDE
    }

    private final Mode mode;
    private final ProductCatalogFacts catalog;
    private final int popularSize;
    private final PopularitySource popularitySource;
    private final String popularityWindow;
    private final ProductPopularityRepository popularity;
    private final Counter realSignal;
    private final Counter fallbackSignal;

    private volatile boolean warnedAboutFallback;
    private volatile boolean loggedRealSignal;

    private volatile List<Long> cached;

    public ItemPoolSource(@Value("${app.recommendation.item-pool:CATALOG}") Mode mode,
                          @Value("${app.recommendation.result-size:12}") int popularSize,
                          @Value("${app.recommendation.popularity-source:AUTO}") PopularitySource popularitySource,
                          @Value("${app.recommendation.popularity-window:recent_7d}") String popularityWindow,
                          ProductPopularityRepository popularity,
                          ProductCatalogFacts catalog,
                          MeterRegistry registry) {
        this.mode = mode;
        this.popularSize = Math.max(popularSize, 1);
        this.popularitySource = popularitySource;
        this.popularityWindow = popularityWindow;
        this.popularity = popularity;
        this.catalog = catalog;
        // **인기 신호가 실제로 쓰였는지**를 지표로 남긴다 — "인기를 붙였다"가 주장으로 끝나지 않게.
        // 폴백은 정상 상태에서 0 이어야 하고, 0 이 아니면 "인기가 아니라 퍼뜨리기"가 나가고 있다는 뜻이다.
        this.realSignal = Counter.builder("recommendation.popular.signal.real")
                .description("인기 통계를 실제로 읽어 채운 횟수")
                .register(registry);
        this.fallbackSignal = Counter.builder("recommendation.popular.signal.fallback")
                .description("인기 통계가 없어 후보 집합에 퍼뜨린 횟수")
                .register(registry);
        log.info("추천 후보 집합={} (CATALOG=실제 상품 · EXPERIMENT=실험용 합성 풀) · 인기 신호={} 창={}",
                mode, popularitySource, popularityWindow);
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
     * 모델이 "인기 상품"으로 채울 때 쓰는 id.
     *
     * <p><b>이제 진짜 인기다(#198②)</b>: M1 이 3,178만 거래로 센 상위 상품을 읽는다
     * ({@code product_popularity}, 창 = {@code app.recommendation.popularity-window}). 그 전에는
     * 아래 퍼뜨리기(stride)가 이 자리를 대신했고, <b>그건 인기가 아니라 편향 회피였다</b> —
     * 이름만 "인기"였던 것이다.
     *
     * <p><b>왜 퍼뜨리기가 남아 있는가</b>: 인기 통계는 오프라인 배치가 적재하므로, **적재 전에는 표가
     * 비어 있다**(그리고 배치가 실패할 수도 있다). 그때 홈의 인기 행이 통째로 비면 화면이 망가지므로
     * 폴백을 남긴다. 대신 <b>조용히 넘어가지 않는다</b>: {@code recommendation.popular.signal.fallback}
     * 지표와 경고 로그가 "지금 인기가 아니라 퍼뜨리기를 내보내고 있다"를 밝힌다.
     * 퍼뜨리기가 무엇을 하는지는 아래 {@link #spreadAcrossCatalog()} 주석에 있다.
     *
     * <p><b>캐시하지 않는다</b>: 12행 인덱스 조회라 비용이 무시할 만하고, 캐시하면 배치 적재 뒤
     * 재기동이 필요해진다(값이 바뀌었는데 화면이 옛 인기를 보여주는 상태가 조용히 생긴다).
     */
    public List<Long> popular() {
        return popular(popularSize);
    }

    /**
     * 인기 목록을 {@code limit} 개까지 — <b>되채우기용 깊이</b>(#198①).
     *
     * <p>인기 표는 200행을 들고 있으므로 순위를 더 내려가도 조회는 여전히 인덱스 한 페이지다.
     * 깊이를 늘리는 값이 <b>지연이 아니라 관련도</b>라는 것이 이 정책의 요점이다.
     */
    public List<Long> popular(int limit) {
        if (mode == Mode.EXPERIMENT) {
            return ItemPool.POPULAR;
        }
        int want = Math.max(limit, 1);
        if (popularitySource == PopularitySource.STRIDE) {
            return spreadAcrossCatalog(want);
        }
        List<Long> real = realPopularity(want);
        if (!real.isEmpty()) {
            realSignal.increment();
            logRealSignalOnce();
            return real;
        }
        fallbackSignal.increment();
        if (!warnedAboutFallback) {
            warnedAboutFallback = true;
            log.warn("인기 통계가 비어 있다 — 후보 집합에 퍼뜨려 채운다. 창={} "
                            + "(적재: personalization/pipeline/export_popular.py --emit-sql --load)",
                    popularityWindow);
        }
        return spreadAcrossCatalog(want);
    }

    /** 인기 통계에서 순위 순으로 읽는다. 표가 비었으면 빈 목록(예외가 아니다 — 적재 전이 정상 상태다). */
    private List<Long> realPopularity(int limit) {
        try {
            List<ProductPopularity> rows = popularity.findByWindowKindOrderByRankNoAsc(
                    popularityWindow, PageRequest.of(0, limit));
            return rows.stream().map(ProductPopularity::getProductId).toList();
        } catch (RuntimeException e) {
            // 표가 아직 없을 수도 있다(마이그레이션 전 부팅). 홈을 죽이지 않고 폴백으로 간다.
            log.warn("인기 통계를 읽지 못했다 — 폴백으로 간다. cause={}", e.toString());
            return List.of();
        }
    }

    private void logRealSignalOnce() {
        if (loggedRealSignal) {
            return;
        }
        loggedRealSignal = true;
        // "요즘"이 언제인지 밝힌다 — 이 데이터는 2020년에 끝나므로 그 사실이 로그에 남아야 한다.
        log.info("인기 신호를 읽었다 — 창={} 기준일={} (개수={})",
                popularityWindow, popularity.latestComputedAt(popularityWindow), popularSize);
    }

    /**
     * 후보 집합에 <b>결정적 간격으로 퍼뜨린다</b> — 인기 흉내가 아니라 <b>카테고리 편향 회피</b>다.
     *
     * <p><b>왜 앞에서 자르지 않는가(M7 실측)</b>: {@code subList(0, 12)} 로 앞을 잘랐더니 그 12개가
     * <b>전부 한 대분류</b>였다 — H&M 상품 id 가 카테고리별로 뭉쳐 있어서 id 순서는 곧 카테고리 순서다.
     * 그 결과 홈이 <b>한 카테고리짜리 화면</b>이 되었고(실측 distinct 대분류 = 1), 다양성 규칙이
     * 그 뒤에서 조용히 항목을 버렸다. **인기 신호가 없는 것을 id 순서로 대신하면 안 된다.**
     */
    private List<Long> spreadAcrossCatalog(int want) {
        List<Long> all = catalogIds();
        int take = Math.min(want, all.size());
        if (take == 0) {
            return List.of();
        }
        List<Long> spread = new java.util.ArrayList<>(take);
        for (int i = 0; i < take; i++) {
            spread.add(all.get((int) ((long) i * all.size() / take)));
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
