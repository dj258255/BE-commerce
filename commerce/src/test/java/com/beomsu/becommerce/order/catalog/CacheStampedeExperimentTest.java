package com.beomsu.becommerce.order.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 인기 상품 캐시 만료 몰림(26절④, Meta "Scaling Memcache at Facebook"의 lease) — 나이브 TTL 캐시 대
 * lease/single-flight 의 만료 순간 DB 도달 조회 수 · p95 비교.
 *
 * <p>기존 {@link FacetCache}(ADR-044)는 TTL 만료에 보호가 없다 — {@code get()}이 만료를 보면
 * 호출자마다 각자 {@code loader}를 부른다. 이 실험은 그 로직을 그대로 옮긴 {@link NaiveTtlCache}와,
 * 같은 키에 진행 중인 로드가 있으면 새로 시작하지 않고 결과를 나눠 쓰는 {@link SingleFlightTtlCache}를
 * 같은 로더 · 같은 만료 순간 · 같은 동시성으로 비교한다.
 *
 * <p><b>DB를 유한 자원으로 모사한 이유</b>: 로더를 무제한 병렬로 두면(스레드가 충분하면) NAIVE 의 N개
 * 호출도 전부 동시에 실행돼 벽시계 지연이 로더 지연과 비슷하게 끝난다 — 이러면 "DB 보호"라는 이 실험의
 * 요점(과부하)이 드러나지 않는다. 그래서 로더를 이 저장소 Hikari 기본값(ADR-022,
 * {@code maximum-pool-size: 20})과 같은 크기의 고정 스레드 풀에서 돌려, 동시 로더 호출이 풀보다 많으면
 * 큐잉이 생기게 했다. SINGLEFLIGHT 는 만료마다 이 풀에 로더를 <b>한 번만</b> 제출한다.
 *
 * <p>{@code ./gradlew experimentTest --tests '*CacheStampede*'}로 실행한다(Docker 불필요, 순수 JUnit).
 */
@Tag("experiment")
class CacheStampedeExperimentTest {

    private static final long LOADER_DELAY_MS = 100;
    private static final int DB_POOL_SIZE = 20; // ADR-022 Hikari maximum-pool-size 와 동일
    private static final Duration TTL = Duration.ofMillis(50);
    private static final int[] CONCURRENCIES = {10, 50, 100};

    @Test
    @DisplayName("만료 순간 동시성 10/50/100에서 NAIVE 대 SINGLEFLIGHT의 DB 도달 호출 수·p95를 비교한다")
    void singleFlightBoundsDbCallsAtExpiry() throws Exception {
        Map<Integer, Result> naive = new java.util.LinkedHashMap<>();
        Map<Integer, Result> singleFlight = new java.util.LinkedHashMap<>();
        for (int concurrency : CONCURRENCIES) {
            naive.put(concurrency, run("NAIVE", concurrency, false));
            singleFlight.put(concurrency, run("SINGLEFLIGHT", concurrency, true));
        }

        for (int concurrency : CONCURRENCIES) {
            Result n = naive.get(concurrency);
            Result s = singleFlight.get(concurrency);
            System.out.printf("CACHE-STAMPEDE cond=%s concurrency=%d loader_calls=%d p50=%dms p95=%dms max=%dms%n",
                    n.name, n.concurrency, n.loaderCalls, n.p50, n.p95, n.max);
            System.out.printf("CACHE-STAMPEDE cond=%s concurrency=%d loader_calls=%d p50=%dms p95=%dms max=%dms%n",
                    s.name, s.concurrency, s.loaderCalls, s.p50, s.p95, s.max);
        }

        // 판정 기준(이슈 #417, 측정 전에 적음) — 조건이 실제로 섰다는 증거부터 확인한다.
        for (int concurrency : CONCURRENCIES) {
            Result n = naive.get(concurrency);
            Result s = singleFlight.get(concurrency);
            // 1. NAIVE 는 만료를 본 호출자마다 각자 로더를 불러 호출 수가 동시성과 같다.
            assertThat(n.loaderCalls).isEqualTo(concurrency);
            // 2. SINGLEFLIGHT 는 만료마다 로더를 한 번만 부른다 — 조건이 실제로 섰다는 증거.
            assertThat(s.loaderCalls).isEqualTo(1);
        }
    }

    private record Result(String name, int concurrency, int loaderCalls, long p50, long p95, long max) {
    }

    private Result run(String name, int concurrency, boolean singleFlight) throws Exception {
        ExecutorService dbPool = Executors.newFixedThreadPool(DB_POOL_SIZE);
        AtomicInteger loaderCalls = new AtomicInteger();
        Supplier<String> loader = () -> {
            loaderCalls.incrementAndGet();
            try {
                Thread.sleep(LOADER_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "popular-products-snapshot";
        };
        try {
            TtlCache<String, String> cache = singleFlight
                    ? new SingleFlightTtlCache<>(TTL, dbPool)
                    : new NaiveTtlCache<>(TTL, dbPool);

            // 예열: 캐시를 한 번 채운다. 이 호출은 만료 몰림 통계에서 뺀다.
            cache.get("popular-products", loader);
            loaderCalls.set(0);
            Thread.sleep(TTL.toMillis() + 10); // 만료를 확실히 넘긴다

            ExecutorService callers = Executors.newFixedThreadPool(concurrency);
            CyclicBarrier barrier = new CyclicBarrier(concurrency);
            List<Long> latencies = Collections.synchronizedList(new ArrayList<>());
            CountDownLatch done = new CountDownLatch(concurrency);
            for (int i = 0; i < concurrency; i++) {
                callers.submit(() -> {
                    try {
                        barrier.await(10, TimeUnit.SECONDS); // 만료 순간을 동시에 때린다
                        long began = System.nanoTime();
                        cache.get("popular-products", loader);
                        latencies.add((System.nanoTime() - began) / 1_000_000);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            callers.shutdown();

            List<Long> sorted = new ArrayList<>(latencies);
            Collections.sort(sorted);
            return new Result(name, concurrency, loaderCalls.get(),
                    percentile(sorted, 0.50), percentile(sorted, 0.95), sorted.get(sorted.size() - 1));
        } finally {
            dbPool.shutdownNow();
        }
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }

    /** 비교할 두 캐시가 구현하는 공통 계약. */
    private interface TtlCache<K, V> {
        V get(K key, Supplier<V> loader);
    }

    /**
     * {@link FacetCache}(ADR-044)와 같은 로직 — 만료를 본 호출자마다 각자 로더를 부른다. 보호가 없다.
     */
    private static final class NaiveTtlCache<K, V> implements TtlCache<K, V> {
        private record Entry<V>(V value, long expiresAtNanos) {
        }

        private final Map<K, Entry<V>> cache = new ConcurrentHashMap<>();
        private final long ttlNanos;
        private final ExecutorService dbPool;

        NaiveTtlCache(Duration ttl, ExecutorService dbPool) {
            this.ttlNanos = ttl.toNanos();
            this.dbPool = dbPool;
        }

        @Override
        public V get(K key, Supplier<V> loader) {
            long now = System.nanoTime();
            Entry<V> cached = cache.get(key);
            if (cached != null && cached.expiresAtNanos() > now) {
                return cached.value();
            }
            V value = callThroughDbPool(loader);
            cache.put(key, new Entry<>(value, System.nanoTime() + ttlNanos));
            return value;
        }

        private V callThroughDbPool(Supplier<V> loader) {
            try {
                return dbPool.submit(loader::get).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                throw new IllegalStateException(e.getCause());
            }
        }
    }

    /**
     * lease/single-flight — 같은 키에 진행 중인 로드가 있으면 새로 시작하지 않고 그 결과를 기다렸다가
     * 나눠 쓴다. 만료마다 DB(여기서는 {@code dbPool})에 닿는 로더 호출은 동시성과 무관하게 하나다.
     */
    private static final class SingleFlightTtlCache<K, V> implements TtlCache<K, V> {
        private record Entry<V>(V value, long expiresAtNanos) {
        }

        private final Map<K, Entry<V>> cache = new ConcurrentHashMap<>();
        private final Map<K, CompletableFuture<Entry<V>>> inFlight = new ConcurrentHashMap<>();
        private final long ttlNanos;
        private final ExecutorService dbPool;

        SingleFlightTtlCache(Duration ttl, ExecutorService dbPool) {
            this.ttlNanos = ttl.toNanos();
            this.dbPool = dbPool;
        }

        @Override
        public V get(K key, Supplier<V> loader) {
            long now = System.nanoTime();
            Entry<V> cached = cache.get(key);
            if (cached != null && cached.expiresAtNanos() > now) {
                return cached.value();
            }
            CompletableFuture<Entry<V>> future = inFlight.computeIfAbsent(key, k ->
                    CompletableFuture.supplyAsync(
                            () -> new Entry<>(loader.get(), System.nanoTime() + ttlNanos), dbPool));
            try {
                Entry<V> entry = future.get(30, TimeUnit.SECONDS);
                cache.put(key, entry);
                return entry.value();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } catch (ExecutionException | TimeoutException e) {
                throw new IllegalStateException(e);
            } finally {
                inFlight.remove(key, future);
            }
        }
    }
}
