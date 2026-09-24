package com.beomsu.becommerce.order.catalog.search;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 앱 안 Lucene 색인을 <b>주기적으로 새로 만들어 통째로 갈아 끼운다</b>(ADR-051).
 *
 * <p>카탈로그는 앱 밖 파이프라인이 적재하므로 앱은 바뀐 것을 알 수 없다. 그래서 변경을 듣지 않고 주기마다
 * 전부 다시 만든다 — 10만 건에 1.5초, 색인 약 5MB 라 통째로 만드는 편이 증분 반영보다 단순하다.
 * 새 상품이 검색에 보이기까지 최대 한 주기가 걸린다. 가격·재고는 DB 에서 채우므로 이 지연에 걸리지 않는다.
 *
 * <p><b>배치 스케줄러로 돌리지 않는다.</b> 배치는 워커 배포 하나만 돌지만(ADR-029) 이 색인은 <b>인스턴스마다</b>
 * 있으므로 모든 인스턴스가 스스로 갈아야 한다. 그래서 자기 스레드 하나를 쓴다.
 *
 * <p>갈아 끼운 옛 색인은 바로 닫지 않고 {@link #CLOSE_DELAY} 뒤에 닫는다 — 그 순간 옛 색인으로 돌던 검색이
 * 닫힌 색인을 읽지 않게 하려는 것이다. 다시 만들다 실패하면 옛 색인을 그대로 쓰고 실패를 센다.
 */
public class RefreshingLuceneSearch implements ProductSearch, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RefreshingLuceneSearch.class);
    static final Duration CLOSE_DELAY = Duration.ofSeconds(30);

    private final Supplier<List<LuceneProductSearch.Doc>> loader;
    private final AtomicReference<LuceneProductSearch> current = new AtomicReference<>();
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "lucene-refresh");
        t.setDaemon(true);
        return t;
    });
    private final Counter refreshed;
    private final Counter failed;

    public RefreshingLuceneSearch(Supplier<List<LuceneProductSearch.Doc>> loader, Duration interval,
                                  MeterRegistry registry) {
        this.loader = loader;
        this.current.set(build());
        this.refreshed = Counter.builder("catalog.search.lucene.refresh").tag("result", "ok").register(registry);
        this.failed = Counter.builder("catalog.search.lucene.refresh").tag("result", "failed").register(registry);
        Gauge.builder("catalog.search.lucene.index.bytes", current, c -> c.get().ramBytes()).register(registry);
        Gauge.builder("catalog.search.lucene.build.ms", current, c -> c.get().buildMillis()).register(registry);
        Gauge.builder("catalog.search.lucene.docs", current, c -> c.get().docCount()).register(registry);
        if (!interval.isZero() && !interval.isNegative()) {
            executor.scheduleWithFixedDelay(this::refresh, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private LuceneProductSearch build() {
        LuceneProductSearch built = new LuceneProductSearch(loader.get());
        log.info("Lucene 색인 {}건 · {}ms · {}KB", built.docCount(), built.buildMillis(), built.ramBytes() / 1024);
        return built;
    }

    /** 새 색인을 만들어 갈아 끼운다. 테스트와 주기 작업이 부른다. */
    void refresh() {
        try {
            LuceneProductSearch previous = current.getAndSet(build());
            refreshed.increment();
            executor.schedule(() -> closeQuietly(previous), CLOSE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            failed.increment();
            log.warn("Lucene 색인 갱신 실패 — 옛 색인을 계속 쓴다: {}", e.toString());
        }
    }

    @Override
    public SearchPage search(String query, int page, int size) {
        return current.get().search(query, page, size);
    }

    @Override
    public boolean filtersInEngine() {
        return true;
    }

    @Override
    public SearchPage searchFiltered(String query, SearchFilters filters, int page, int size) {
        return current.get().searchFiltered(query, filters, page, size);
    }

    @Override
    public SearchFacets facets(String query, SearchFilters filters) {
        return current.get().facets(query, filters);
    }

    @Override
    public String engine() {
        return "lucene";
    }

    @Override
    public boolean ranksByRelevance() {
        return true;
    }

    int docCount() {
        return current.get().docCount();
    }

    @Override
    public void close() {
        executor.shutdownNow();
        closeQuietly(current.get());
    }

    private static void closeQuietly(LuceneProductSearch search) {
        try {
            search.close();
        } catch (Exception e) {
            log.debug("Lucene 색인 닫기 실패: {}", e.toString());
        }
    }
}
