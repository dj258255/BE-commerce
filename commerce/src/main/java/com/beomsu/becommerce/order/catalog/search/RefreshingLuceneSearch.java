package com.beomsu.becommerce.order.catalog.search;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
 *
 * <p><b>변경 반영</b>(#246): {@link #changed} 가 바뀐 상품 id 를 받아 DB 에서 다시 읽고 문서를 갈아 끼운다. 이벤트 내용을
 * 믿지 않고 DB 를 다시 읽으므로 같은 변경이 두 번 오거나 순서가 바뀌어도 결과는 지금 DB 와 같다. 검색에 보이는 것은
 * 다음 검색기 갱신({@code nrtRefresh}) 뒤다.
 *
 * <p><b>전체 재색인과 겹칠 때</b>: 새 색인은 적재를 시작한 순간의 DB 를 담는다. 그 뒤에 온 변경은 옛 색인에만 들어가
 * 갈아 끼우는 순간 사라진다. 그래서 재색인 중에 온 id 를 모아 두었다가, 갈아 끼우기 직전에 새 색인에 다시 반영한다.
 * 다시 반영과 갈아 끼우기는 {@link #changed} 와 같은 잠금 안에서 한다.
 */
public class RefreshingLuceneSearch implements ProductSearch, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RefreshingLuceneSearch.class);
    static final Duration CLOSE_DELAY = Duration.ofSeconds(30);

    private final Supplier<List<LuceneProductSearch.Doc>> loader;
    private final Function<Collection<Long>, List<LuceneProductSearch.Doc>> byIds;
    private final Object swapLock = new Object();
    /** 재색인 중에 들어온 변경. null 이면 재색인 중이 아니다. {@link #swapLock} 안에서만 읽고 쓴다. */
    private Set<Long> changedDuringBuild;
    private volatile long loadStartedAt;
    private final AtomicReference<LuceneProductSearch> current = new AtomicReference<>();
    /** 스레드 둘: 재색인(수 초)이 도는 동안에도 검색기 갱신(1초)이 밀리지 않게 한다. */
    private final ScheduledExecutorService executor = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "lucene-refresh");
        t.setDaemon(true);
        return t;
    });
    private final Counter refreshed;
    private final Counter failed;
    private final Counter applied;

    public RefreshingLuceneSearch(Supplier<List<LuceneProductSearch.Doc>> loader, Duration interval,
                                  MeterRegistry registry) {
        this(loader, ids -> List.of(), interval, Duration.ZERO, registry);
    }

    /**
     * @param byIds      변경 반영 때 id 로 다시 읽는다. 돌려주지 않은 id 는 지워진 상품이다
     * @param nrtRefresh 바뀐 문서가 검색에 보이게 검색기를 다시 여는 주기. 0 이면 열지 않는다(변경 반영을 안 쓸 때)
     */
    public RefreshingLuceneSearch(Supplier<List<LuceneProductSearch.Doc>> loader,
                                  Function<Collection<Long>, List<LuceneProductSearch.Doc>> byIds,
                                  Duration interval, Duration nrtRefresh, MeterRegistry registry) {
        this.loader = loader;
        this.byIds = byIds;
        this.current.set(build());
        this.refreshed = Counter.builder("catalog.search.lucene.refresh").tag("result", "ok").register(registry);
        this.failed = Counter.builder("catalog.search.lucene.refresh").tag("result", "failed").register(registry);
        this.applied = Counter.builder("catalog.search.lucene.changes").register(registry);
        Gauge.builder("catalog.search.lucene.index.bytes", current, c -> c.get().ramBytes()).register(registry);
        Gauge.builder("catalog.search.lucene.build.ms", current, c -> c.get().buildMillis()).register(registry);
        Gauge.builder("catalog.search.lucene.docs", current, c -> c.get().docCount()).register(registry);
        if (!interval.isZero() && !interval.isNegative()) {
            executor.scheduleWithFixedDelay(this::refresh, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
        }
        if (!nrtRefresh.isZero() && !nrtRefresh.isNegative()) {
            executor.scheduleWithFixedDelay(this::refreshReader, nrtRefresh.toMillis(), nrtRefresh.toMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    /** 바뀐 상품을 DB 에서 다시 읽어 지금 색인에 반영한다. 재색인 중이면 id 를 모아 둔다. */
    public void changed(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        synchronized (swapLock) {
            apply(current.get(), ids);
            if (changedDuringBuild != null) {
                changedDuringBuild.addAll(ids);
            }
        }
        applied.increment(ids.size());
    }

    private void apply(LuceneProductSearch target, Collection<Long> ids) {
        Set<Long> missing = new HashSet<>(ids);
        for (LuceneProductSearch.Doc doc : byIds.apply(ids)) {
            target.upsert(doc);
            missing.remove(doc.productId());
        }
        missing.forEach(target::delete);
    }

    void refreshReader() {
        try {
            current.get().refreshReader();
        } catch (RuntimeException e) {
            log.warn("Lucene 검색기 갱신 실패: {}", e.toString());
        }
    }

    /** 마지막으로 전체 적재를 시작한 시각. 변경 구독이 여기서 조금 앞부터 읽는다({@link LuceneChangeListener}). */
    long loadStartedAt() {
        return loadStartedAt;
    }

    private LuceneProductSearch build() {
        loadStartedAt = System.currentTimeMillis();
        LuceneProductSearch built = new LuceneProductSearch(loader.get());
        log.info("Lucene 색인 {}건 · {}ms · {}KB", built.docCount(), built.buildMillis(), built.ramBytes() / 1024);
        return built;
    }

    /** 새 색인을 만들어 갈아 끼운다. 테스트와 주기 작업이 부른다. */
    void refresh() {
        synchronized (swapLock) {
            changedDuringBuild = new HashSet<>();
        }
        try {
            LuceneProductSearch built = build();
            LuceneProductSearch previous;
            synchronized (swapLock) {
                apply(built, changedDuringBuild);
                built.refreshReader();
                previous = current.getAndSet(built);
                changedDuringBuild = null;
            }
            refreshed.increment();
            executor.schedule(() -> closeQuietly(previous), CLOSE_DELAY.toMillis(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            synchronized (swapLock) {
                changedDuringBuild = null;
            }
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
