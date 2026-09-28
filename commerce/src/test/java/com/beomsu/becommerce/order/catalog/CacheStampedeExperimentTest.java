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
import java.util.concurrent.atomic.AtomicReference;
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
 *
 * <p><b>27절⑫ 추가</b>: 아래 {@code versionCheckPreventsStaleSetButNotSameVersionBug}는 같은 lease
 * 논문이 함께 푸는 다른 문제(무효화-채우기 경합에 따른 stale set)를 다룬다. 이슈 #428 참고.
 */
@Tag("experiment")
class CacheStampedeExperimentTest {

    private static final long LOADER_DELAY_MS = 100;
    private static final int DB_POOL_SIZE = 20; // ADR-022 Hikari maximum-pool-size 와 동일
    private static final Duration TTL = Duration.ofMillis(50);
    private static final int[] CONCURRENCIES = {10, 50, 100};

    // --- 캐시 채우기와 무효화의 경합(27절⑫, Meta "Cache made consistent") ---
    //
    // Memcache lease 원 논문(NSDI 2013)은 "two problems: stale sets and thundering herds"를
    // 함께 푼다. 위 두 테스트는 thundering herd(동시 로드 몰림)만 다뤘다 — 이 절은 나머지 하나,
    // 무효화가 먼저 오고 그 전에 시작된 느린 캐시 채우기가 나중에 옛 값을 write-back 하는 경합을
    // 같은 파일에 더한다(이슈 #428 "합치는 이유" 참고). Meta 사고 사례(같은 블로그, 직접 확인):
    // 캐시에 "metadata=0 @version 4", DB에 "metadata=1 @version 4"가 무한히 남았다 — 오류 처리의
    // "버전이 지정값보다 작으면 지운다"가 같은 버전의 틀린 항목은 못 잡았다. 아래 실험은 그 실패
    // 모드를 재현한다.

    private static final long RACE_LOADER_DELAY_MS = 100;
    private static final int RACE_TRIALS = 21; // 무효화 시각 0~200ms(로더 지연의 2배)를 10ms 간격으로

    private record VersionedValue(String value, long version) {
    }

    private record RaceResult(String name, int totalTrials, int racedTrials, int staleCount,
                               double staleRateAmongRaced) {
    }

    private record TrialOutcome(boolean raced, boolean stale) {
    }

    @Test
    @DisplayName("무효화와 캐시 채우기가 경합할 때 lease 없음·lease·버전 비교의 낡은 값 잔존율을 비교한다")
    void versionCheckPreventsStaleSetButNotSameVersionBug() {
        RaceResult noLease = runRace("NO_LEASE", false, false);
        // 이 경합에는 동시 로더가 하나뿐이라 LEASE(단일 비행)의 중복 제거가 개입할 일이 없다 —
        // write-back 로직이 NO_LEASE와 똑같은 것이 예상된 관찰이다(다른 로더가 있었어도 write-back
        // 시점 문제는 그대로다).
        RaceResult lease = runRace("LEASE", false, false);
        RaceResult versionCheck = runRace("VERSION_CHECK", true, false);
        RaceResult versionCheckSameVersionBug = runRace("VERSION_CHECK_SAME_VERSION_BUG", true, true);

        for (RaceResult r : List.of(noLease, lease, versionCheck, versionCheckSameVersionBug)) {
            System.out.printf("CACHE-INVALIDATION-RACE cond=%s raced=%d/%d stale=%d stale_rate=%.2f%n",
                    r.name, r.racedTrials, r.totalTrials, r.staleCount, r.staleRateAmongRaced);
        }

        // 판정 기준(이슈 #428, 측정 전에 적음) — 조건이 실제로 섰다는 증거부터 확인한다.
        assertThat(noLease.racedTrials).isGreaterThan(0); // 경합이 실제로 생겼다는 증거
        // 1. NO_LEASE는 경합 중 불일치율이 0보다 크다.
        assertThat(noLease.staleRateAmongRaced).isGreaterThan(0.0);
        // 2. LEASE의 불일치율은 NO_LEASE와 뚜렷한 차이가 없다(둘 다 write-back에 버전 확인이 없다).
        assertThat(Math.abs(lease.staleRateAmongRaced - noLease.staleRateAmongRaced)).isLessThan(0.15);
        // 3. VERSION_CHECK(정상 무효화)의 불일치율이 NO_LEASE·LEASE보다 뚜렷이 낮고 0에 가깝다.
        assertThat(versionCheck.staleRateAmongRaced).isLessThan(noLease.staleRateAmongRaced);
        assertThat(versionCheck.staleRateAmongRaced).isLessThan(lease.staleRateAmongRaced);
        assertThat(versionCheck.staleRateAmongRaced).isLessThan(0.1);
        // 4. 같은 버전으로 값만 바꾸는 무효화(Meta 사고 재현)에서는 VERSION_CHECK도 못 막는다.
        assertThat(versionCheckSameVersionBug.staleRateAmongRaced).isGreaterThan(versionCheck.staleRateAmongRaced);
    }

    private RaceResult runRace(String name, boolean versionCheck, boolean sameVersionBug) {
        int raced = 0;
        int stale = 0;
        for (int i = 0; i < RACE_TRIALS; i++) {
            long offsetMs = i * (2 * RACE_LOADER_DELAY_MS) / (RACE_TRIALS - 1);
            TrialOutcome outcome = runTrial(offsetMs, versionCheck, sameVersionBug);
            if (outcome.raced()) {
                raced++;
                if (outcome.stale()) {
                    stale++;
                }
            }
        }
        double rate = raced > 0 ? (double) stale / raced : 0.0;
        return new RaceResult(name, RACE_TRIALS, raced, stale, rate);
    }

    /**
     * 로더 하나(무효화 이전 버전을 읽고 {@code RACE_LOADER_DELAY_MS} 뒤 write-back)와 무효화 하나
     * (임의 시각에 DB와 캐시를 함께 새 값으로 씀)를 동시에 돌려 최종 캐시가 DB와 일치하는지 본다.
     */
    private TrialOutcome runTrial(long invalidateOffsetMs, boolean versionCheck, boolean sameVersionBug) {
        AtomicReference<VersionedValue> db = new AtomicReference<>(new VersionedValue("v0", 1));
        AtomicReference<VersionedValue> cache = new AtomicReference<>(new VersionedValue("v0", 1));
        CountDownLatch loaderStarted = new CountDownLatch(1);

        Thread loaderThread = new Thread(() -> {
            VersionedValue readAtStart = db.get(); // 로드 시작 시점의 DB 스냅숏
            loaderStarted.countDown();
            sleepQuiet(RACE_LOADER_DELAY_MS);
            if (versionCheck) {
                // "버전이 지정값보다 작으면 거부" — Meta 사고처럼 버전이 같으면 통과시킨다(버그 재현 지점).
                cache.getAndUpdate(current -> readAtStart.version() >= current.version() ? readAtStart : current);
            } else {
                cache.set(readAtStart); // NO_LEASE·LEASE: 버전 확인 없이 무조건 덮어쓴다
            }
        });
        Thread invalidatorThread = new Thread(() -> {
            awaitQuiet(loaderStarted);
            sleepQuiet(invalidateOffsetMs);
            long newVersion = sameVersionBug ? db.get().version() : db.get().version() + 1;
            VersionedValue newValue = new VersionedValue("v1", newVersion);
            db.set(newValue);
            cache.set(newValue); // 무효화가 write-through로 캐시에도 새 값을 직접 쓴다
        });

        loaderThread.start();
        invalidatorThread.start();
        joinQuiet(loaderThread);
        joinQuiet(invalidatorThread);

        boolean raced = invalidateOffsetMs < RACE_LOADER_DELAY_MS; // 무효화가 로드 완료 전에 시작됐다
        boolean stale = !cache.get().equals(db.get());
        return new TrialOutcome(raced, stale);
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuiet(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuiet(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

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
