package com.beomsu.becommerce.order.catalog.search;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 엔진이 실패하면 DB 검색으로 물러선다. <b>엔진이 죽어도 검색 화면은 뜬다.</b>
 *
 * <p>물러선 사실은 {@code catalog.search.fallback{engine}} 으로 센다. 조용한 강등은 강등이 아니라 사고다 —
 * 결과가 나오니 아무도 엔진이 죽은 줄 모른다.
 *
 * <p>물러선 결과는 관련도 순서가 아니다(신상품순). 호출자는 {@link #engine()} 대신 실제로 답한 쪽을
 * 알아야 순서를 해석할 수 있으므로 {@link Answer} 로 돌려준다.
 */
public class FallbackProductSearch implements ProductSearch {

    private static final Logger log = LoggerFactory.getLogger(FallbackProductSearch.class);

    private final ProductSearch primary;
    private final ProductSearch fallback;
    private final CandidateFiltering candidates;
    private final Counter fallbacks;

    public FallbackProductSearch(ProductSearch primary, ProductSearch fallback, MeterRegistry registry) {
        this(primary, fallback, null, registry);
    }

    /** {@code candidates} 가 있으면 엔진 안 필터·패싯이 실패할 때 후보 자르기(DB)로 물러선다(#244). */
    public FallbackProductSearch(ProductSearch primary, ProductSearch fallback, CandidateFiltering candidates,
                                 MeterRegistry registry) {
        this.primary = primary;
        this.fallback = fallback;
        this.candidates = candidates;
        this.fallbacks = Counter.builder("catalog.search.fallback")
                .description("검색 엔진이 실패해 DB 검색으로 물러선 횟수")
                .tag("engine", primary.engine())
                .register(registry);
    }

    /** 답한 쪽과 결과. */
    public record Answer(ProductSearch answeredBy, SearchPage page) {
    }

    public Answer answer(String query, int page, int size) {
        try {
            return new Answer(primary, primary.search(query, page, size));
        } catch (RuntimeException e) {
            fallbacks.increment();
            log.warn("검색 엔진 {} 실패 → {} 로 물러선다: {}", primary.engine(), fallback.engine(), e.toString());
            return new Answer(fallback, fallback.search(query, page, size));
        }
    }

    @Override
    public SearchPage search(String query, int page, int size) {
        return answer(query, page, size).page();
    }

    @Override
    public boolean filtersInEngine() {
        return primary.filtersInEngine() && candidates != null;
    }

    @Override
    public SearchPage searchFiltered(String query, SearchFilters filters, int page, int size) {
        try {
            return primary.searchFiltered(query, filters, page, size);
        } catch (RuntimeException e) {
            fallbacks.increment();
            log.warn("검색 엔진 {} 필터 검색 실패 → 후보 자르기로 물러선다: {}", primary.engine(), e.toString());
            return candidates.filter(fallback, query, filters, page, size);
        }
    }

    @Override
    public SearchFacets facets(String query, SearchFilters filters) {
        try {
            return primary.facets(query, filters);
        } catch (RuntimeException e) {
            fallbacks.increment();
            log.warn("검색 엔진 {} 패싯 실패 → 후보 자르기로 물러선다: {}", primary.engine(), e.toString());
            return candidates.facets(fallback, query, filters);
        }
    }

    @Override
    public String engine() {
        return primary.engine();
    }

    @Override
    public boolean ranksByRelevance() {
        return primary.ranksByRelevance();
    }
}
