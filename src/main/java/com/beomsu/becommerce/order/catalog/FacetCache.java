package com.beomsu.becommerce.order.catalog;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 패싯 사전 집계 — <b>요청마다 세지 않는다</b>(ADR-044의 1단계).
 *
 * <p><b>왜 필요한가(실측)</b>: 검색 베이스라인에서 <b>패싯이 페이지 지연의 82~90%</b> 를 차지했다
 * (동시성 1에서도 82.5%). 패싯은 <b>카탈로그가 바뀔 때만</b> 달라지므로 요청마다 두 축을 집계하는 것은
 * 낭비다. 그 측정이 [ADR-044](../../../../../../docs/adr/ADR-044-no-search-engine-yet.md) 의 결정 근거다.
 *
 * <p><b>무효화를 어떻게 하는가 — 그리고 왜 TTL인가</b>: 이상적으로는 "카탈로그가 바뀌면 즉시"다.
 * 그런데 이 저장소의 카탈로그는 <b>앱 밖에서</b> 적재된다(`pipeline/promote_products.py`). 앱은 그 사건을
 * 볼 수 없으므로 <b>버전 카운터로 즉시 무효화할 수 없다.</b> 그래서 TTL을 쓴다 — 그 대가는
 * <b>TTL 창 안에서 낡은 개수를 보여주는 것</b>이고, 이건 숨기지 않고 문서에 적었다.
 * (파이프라인이 버전 행을 올려 주면 즉시 무효화로 바꿀 수 있다 — 「다시 볼 조건」.)
 *
 * <p><b>상한이 있는 이유</b>: 캐시는 상한이 없으면 메모리 누수다. 필터 조합의 키 공간이 작지 않으므로
 * (대분류 × 색상 × 종류 × 가격 범위) 상한에 닿으면 <b>통째로 비운다</b> — 축출 정책을 정교하게 만들
 * 만한 근거가 아직 없다(그 결정도 측정이 필요하다).
 *
 * <p><b>지표를 남기는 이유</b>: 적중률이 낮으면 이 캐시는 메모리만 쓰고 있는 것이다. 그 사실이
 * 보이지 않으면 "캐시를 붙였다"가 성과처럼 남는다.
 */
@Component
public class FacetCache {

    private static final Logger log = LoggerFactory.getLogger(FacetCache.class);

    private record Entry(FacetView view, long expiresAtNanos) {
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();
    private final long ttlNanos;
    private final int maxEntries;
    private final Counter hits;
    private final Counter misses;
    private final Counter evictions;

    public FacetCache(MeterRegistry registry,
                      @Value("${app.catalog.facets.cache-ttl:60s}") Duration ttl,
                      @Value("${app.catalog.facets.cache-max-entries:200}") int maxEntries) {
        this.ttlNanos = Math.max(ttl.toNanos(), 0);
        this.maxEntries = Math.max(maxEntries, 1);
        this.hits = Counter.builder("catalog.facets.cache").tag("result", "hit").register(registry);
        this.misses = Counter.builder("catalog.facets.cache").tag("result", "miss").register(registry);
        this.evictions = Counter.builder("catalog.facets.cache").tag("result", "evicted").register(registry);
        log.info("패싯 사전 집계={} TTL={} 상한={}개", ttlNanos > 0 ? "켜짐" : "꺼짐(TTL 0)", ttl, this.maxEntries);
    }

    /**
     * 키에 대한 패싯을 돌려준다. 없거나 만료면 {@code loader} 로 <b>한 번만</b> 만든다.
     *
     * <p>{@code TTL=0} 이면 항상 {@code loader} 를 부른다 — <b>끄는 것이 측정의 기준선</b>이기 때문이다
     * (비교하려면 같은 하네스로 켠 것과 끈 것을 둘 다 재야 한다).
     */
    public FacetView get(String key, Supplier<FacetView> loader) {
        if (ttlNanos == 0) {
            return loader.get();
        }
        long now = System.nanoTime();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAtNanos() > now) {
            hits.increment();
            return cached.view();
        }
        FacetView view = loader.get();
        if (cache.size() >= maxEntries) {
            int before = cache.size();
            cache.clear();
            evictions.increment(before);
            log.debug("패싯 캐시 상한({})에 닿아 비웠다 — {}개", maxEntries, before);
        }
        cache.put(key, new Entry(view, now + ttlNanos));
        misses.increment();
        return view;
    }
}
