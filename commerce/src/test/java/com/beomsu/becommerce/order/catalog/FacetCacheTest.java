package com.beomsu.becommerce.order.catalog;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 패싯 사전 집계의 <b>계약</b>을 고정한다 — ADR-044의 1단계.
 *
 * <p>보는 것 셋:
 * <ul>
 *   <li><b>TTL 안에서는 다시 세지 않는다</b> — 이게 이 캐시의 존재 이유다(실측: 패싯이 페이지
 *       지연의 82~90%)</li>
 *   <li><b>TTL=0이면 항상 센다</b> — 끄는 것이 측정의 <b>기준선</b>이라 그 경로가 살아 있어야 한다</li>
 *   <li><b>상한에 닿으면 비운다</b> — 상한 없는 캐시는 메모리 누수다</li>
 * </ul>
 */
class FacetCacheTest {

    private final FacetView view = new FacetView(List.of(), List.of());

    private FacetCache cache(Duration ttl, int maxEntries) {
        return new FacetCache(new SimpleMeterRegistry(), ttl, maxEntries);
    }

    @Test
    @DisplayName("TTL 안에서는 로더를 한 번만 부른다 — 요청마다 세지 않는 것이 이 캐시의 이유다")
    void loadsOnceWithinTtl() {
        FacetCache cache = cache(Duration.ofSeconds(60), 10);
        AtomicInteger loads = new AtomicInteger();

        cache.get("k", () -> { loads.incrementAndGet(); return view; });
        cache.get("k", () -> { loads.incrementAndGet(); return view; });
        cache.get("k", () -> { loads.incrementAndGet(); return view; });

        assertThat(loads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("키가 다르면 따로 센다 — 필터 조합이 키다")
    void differentKeysLoadSeparately() {
        FacetCache cache = cache(Duration.ofSeconds(60), 10);
        AtomicInteger loads = new AtomicInteger();

        cache.get("a", () -> { loads.incrementAndGet(); return view; });
        cache.get("b", () -> { loads.incrementAndGet(); return view; });

        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("TTL=0이면 항상 센다 — 끈 상태가 측정의 기준선이다")
    void ttlZeroAlwaysLoads() {
        FacetCache cache = cache(Duration.ZERO, 10);
        AtomicInteger loads = new AtomicInteger();

        cache.get("k", () -> { loads.incrementAndGet(); return view; });
        cache.get("k", () -> { loads.incrementAndGet(); return view; });

        assertThat(loads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("상한에 닿으면 비운다 — 상한 없는 캐시는 메모리 누수다")
    void evictsWhenFull() {
        FacetCache cache = cache(Duration.ofSeconds(60), 2);
        AtomicInteger loads = new AtomicInteger();

        cache.get("a", () -> { loads.incrementAndGet(); return view; });
        cache.get("b", () -> { loads.incrementAndGet(); return view; });
        // 세 번째가 상한을 넘겨 캐시를 비운다 — 그러면 a 를 다시 물을 때 로더가 다시 돈다.
        cache.get("c", () -> { loads.incrementAndGet(); return view; });
        cache.get("a", () -> { loads.incrementAndGet(); return view; });

        assertThat(loads.get()).as("비운 뒤에는 다시 센다").isEqualTo(4);
    }
}
