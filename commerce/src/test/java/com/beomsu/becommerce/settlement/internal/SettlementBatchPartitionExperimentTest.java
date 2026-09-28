package com.beomsu.becommerce.settlement.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 배치 병렬화 단위(27절⑧, 토스페이먼츠 "레거시 정산 개편기") — 나누는 키(판매자 id 모듈러 대 항목 id
 * 범위 대 판매자 크기로 묶어 배정)별 스레드 부하 균형·판매자 분산·정산 불변식.
 *
 * <p>{@link SettlementService#settle}은 지금 단일 스레드로 판매자별 순회하며 집계한다(34~35행 주석:
 * "대용량은 Spring Batch로 확장한다"는 미검증). 이 실험은 그 배치를 여러 스레드로 나눠 처리할 때
 * 세 가지 나누기가 각각 무엇을 대가로 치르는지 잰다. {@code SettlementService}에는 배선하지 않는다.
 *
 * <ul>
 *   <li><b>MODULO</b> — 판매자 id를 스레드 수로 나눈 나머지로 배정한다(토스 사례의 "모듈러 연산").
 *       한 판매자의 항목은 항상 같은 스레드에 있어 판매자별 집계에 동시 쓰기가 없다. 다만 대형
 *       판매자 하나가 스큐 분포에서 한 스레드에 몰릴 수 있다.</li>
 *   <li><b>RANGE</b> — 항목 id 순서대로 균등하게 자른다(토스 사례의 "날짜 기반 Range 파티셔닝"을
 *       항목 id로 근사). 스레드별 건수는 균등하지만 판매자 경계를 모르고 자르므로 한 판매자의
 *       항목이 여러 스레드에 흩어질 수 있다 — 판매자별 집계에 동시 쓰기가 생긴다.</li>
 *   <li><b>SIZE_GROUPED</b> — 판매자별 총 항목 수를 먼저 구해 LPT(longest processing time first)
 *       bin-packing으로 스레드에 배정한다. 판매자는 항상 통째로 한 스레드에 있으면서도 스레드별
 *       총량은 균등해진다.</li>
 * </ul>
 *
 * <p>{@code BatchWorkerFailoverExperimentTest}(#420)와 같은 순수 JUnit 실험(Docker 불필요)이다.
 * 항목당 처리는 실제 DB I/O 없이 원장 분개에 준하는 산술(수수료 계산)만 하고, 판매자별 gross 합산은
 * {@link ConcurrentHashMap}의 원자적 {@code addAndGet}으로 모은다 — 동시 쓰기 자체가 사고인지, 원자적
 * 병합이면 안전한지를 구분해서 본다.
 *
 * <p>{@code ./gradlew experimentTest --tests '*SettlementBatchPartition*'}로 실행한다.
 */
@Tag("experiment")
class SettlementBatchPartitionExperimentTest {

    private static final int ITEM_COUNT = 200_000; // 토스 사례(100만 건)에서 반복 실행이 되도록 축소
    private static final int SELLER_COUNT = 200;
    private static final int THREADS_BASELINE = 1;
    private static final int THREADS_PARALLEL = 4;

    private enum Distribution {UNIFORM, SKEWED}

    private enum Strategy {MODULO, RANGE, SIZE_GROUPED}

    private record Item(int index, long sellerId, long amount) {
    }

    private record Result(String label, int totalProcessed, double imbalanceRatio, int spreadSellerCount,
                           int invariantViolations, long totalMs) {
    }

    @Test
    @DisplayName("나누는 키별 스레드 부하 균형·판매자 분산·정산 불변식을 비교한다")
    void comparePartitionStrategies() throws Exception {
        List<Result> results = new ArrayList<>();

        // 직렬 기준(스레드 1) — 완료 시간 참고용, 불균형·분산 지표는 정의상 자명하다.
        results.add(runOne("SERIAL/UNIFORM", Distribution.UNIFORM, Strategy.MODULO, THREADS_BASELINE));

        for (Distribution dist : Distribution.values()) {
            for (Strategy strategy : Strategy.values()) {
                results.add(runOne(strategy + "/" + dist, dist, strategy, THREADS_PARALLEL));
            }
        }

        for (Result r : results) {
            System.out.printf(
                    "SETTLEMENT-PARTITION cond=%s processed=%d imbalance=%.2f spread_sellers=%d "
                            + "invariant_violations=%d total_ms=%d%n",
                    r.label, r.totalProcessed, r.imbalanceRatio, r.spreadSellerCount,
                    r.invariantViolations, r.totalMs);
        }

        Map<String, Result> byLabel = new HashMap<>();
        for (Result r : results) {
            byLabel.put(r.label, r);
        }

        // 판정 기준(이슈 #424, 측정 전에 적음) — 조건이 실제로 섰다는 증거부터 확인한다.
        for (Result r : results) {
            // 1. 정확성: 어느 방식·분포에서도 처리된 항목 건수가 입력 건수와 정확히 같다.
            assertThat(r.totalProcessed).isEqualTo(ITEM_COUNT);
            // 2. 정산 불변식: 판매자별 항목 합 = 정산 gross. 위반 0건(모든 방식·분포).
            assertThat(r.invariantViolations).isEqualTo(0);
        }

        // 3. 불균형: SKEWED 에서 MODULO 의 스레드별 처리 건수 불균형이 RANGE·SIZE_GROUPED 보다 뚜렷이 크다.
        Result moduloSkewed = byLabel.get("MODULO/SKEWED");
        Result rangeSkewed = byLabel.get("RANGE/SKEWED");
        Result sizeGroupedSkewed = byLabel.get("SIZE_GROUPED/SKEWED");
        assertThat(moduloSkewed.imbalanceRatio).isGreaterThan(rangeSkewed.imbalanceRatio);
        assertThat(moduloSkewed.imbalanceRatio).isGreaterThan(sizeGroupedSkewed.imbalanceRatio);

        // 4. 판매자 분산: RANGE 에서 항목이 둘 이상의 스레드에 걸친 판매자 수가 SIZE_GROUPED 보다 뚜렷이 크다.
        assertThat(rangeSkewed.spreadSellerCount).isGreaterThan(sizeGroupedSkewed.spreadSellerCount);
        Result rangeUniform = byLabel.get("RANGE/UNIFORM");
        Result sizeGroupedUniform = byLabel.get("SIZE_GROUPED/UNIFORM");
        assertThat(rangeUniform.spreadSellerCount).isGreaterThan(sizeGroupedUniform.spreadSellerCount);
        // SIZE_GROUPED·MODULO 는 설계상 판매자를 쪼개지 않는다 — 분산 0건이 실측 근거가 아니라
        // 구현이 그렇게 만들었다는 뜻이다(자명한 결과, 아래 "일부러 안 잴 것" 참고).
        assertThat(sizeGroupedSkewed.spreadSellerCount).isEqualTo(0);
        assertThat(sizeGroupedUniform.spreadSellerCount).isEqualTo(0);
    }

    private Result runOne(String label, Distribution dist, Strategy strategy, int threads) throws Exception {
        List<Item> items = buildItems(dist);
        Map<Long, Long> groundTruth = computeGroundTruth(items);
        int[] threadOf = assignThreads(items, strategy, threads);

        long startNanos = System.nanoTime();
        int[] perThreadCount = new int[threads];
        ConcurrentHashMap<Long, AtomicLong> gross = new ConcurrentHashMap<>();
        // 판매자 id 별로 실제 만진 스레드 집합을 기록한다 — RANGE 가 판매자를 몇 스레드에 흩뜨리는지.
        ConcurrentHashMap<Long, Object> sellerThreadMasks = new ConcurrentHashMap<>();

        List<List<Item>> buckets = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            buckets.add(new ArrayList<>());
        }
        for (Item item : items) {
            buckets.get(threadOf[item.index()]).add(item);
        }
        for (int t = 0; t < threads; t++) {
            perThreadCount[t] = buckets.get(t).size();
        }

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadIdx = t;
            futures.add(pool.submit(() -> processBucket(buckets.get(threadIdx), threadIdx, gross, sellerThreadMasks)));
        }
        for (var f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        long totalMs = (System.nanoTime() - startNanos) / 1_000_000;

        int totalProcessed = 0;
        for (int c : perThreadCount) {
            totalProcessed += c;
        }
        double avg = (double) totalProcessed / threads;
        int max = 0;
        for (int c : perThreadCount) {
            max = Math.max(max, c);
        }
        double imbalanceRatio = avg > 0 ? max / avg : 1.0;

        int spreadSellerCount = 0;
        for (Object mask : sellerThreadMasks.values()) {
            @SuppressWarnings("unchecked")
            var set = (java.util.Set<Integer>) mask;
            if (set.size() > 1) {
                spreadSellerCount++;
            }
        }

        int violations = 0;
        for (Map.Entry<Long, Long> e : groundTruth.entrySet()) {
            AtomicLong actual = gross.get(e.getKey());
            long actualValue = actual == null ? 0L : actual.get();
            if (actualValue != e.getValue()) {
                violations++;
            }
        }

        return new Result(label, totalProcessed, imbalanceRatio, spreadSellerCount, violations, totalMs);
    }

    private void processBucket(List<Item> bucket, int threadIdx, ConcurrentHashMap<Long, AtomicLong> gross,
                                ConcurrentHashMap<Long, Object> sellerThreadMasks) {
        for (Item item : bucket) {
            // 원장 분개에 준하는 산술(수수료 계산) — 실제 I/O 없이 CPU 작업만 흉내낸다.
            long fee = Math.multiplyExact(item.amount(), 270L) / 10000L;
            long net = item.amount() - fee;
            gross.computeIfAbsent(item.sellerId(), k -> new AtomicLong()).addAndGet(net);
            sellerThreadMasks.computeIfAbsent(item.sellerId(), k -> java.util.Collections.synchronizedSet(new java.util.HashSet<Integer>()));
            @SuppressWarnings("unchecked")
            var set = (java.util.Set<Integer>) sellerThreadMasks.get(item.sellerId());
            set.add(threadIdx);
        }
    }

    private Map<Long, Long> computeGroundTruth(List<Item> items) {
        Map<Long, Long> truth = new HashMap<>();
        for (Item item : items) {
            long fee = Math.multiplyExact(item.amount(), 270L) / 10000L;
            long net = item.amount() - fee;
            truth.merge(item.sellerId(), net, Long::sum);
        }
        return truth;
    }

    /** UNIFORM은 판매자마다 항목 수가 같다. SKEWED는 판매자 0이 50%, 남은 199곳이 나머지 50%를 나눠 갖는다. */
    private List<Item> buildItems(Distribution dist) {
        List<Item> items = new ArrayList<>(ITEM_COUNT);
        for (int i = 0; i < ITEM_COUNT; i++) {
            long sellerId;
            if (dist == Distribution.UNIFORM) {
                sellerId = i % SELLER_COUNT;
            } else {
                int half = ITEM_COUNT / 2;
                sellerId = i < half ? 0 : 1 + (i - half) % (SELLER_COUNT - 1);
            }
            long amount = 1000 + (i % 7) * 10; // 결정적이지만 획일적이지 않은 금액
            items.add(new Item(i, sellerId, amount));
        }
        return items;
    }

    /** 항목마다 스레드 인덱스(0..threads-1)를 정한다. 반환 배열의 인덱스는 {@link Item#index()}와 같다. */
    private int[] assignThreads(List<Item> items, Strategy strategy, int threads) {
        int[] threadOf = new int[items.size()];
        switch (strategy) {
            case MODULO -> {
                for (Item item : items) {
                    threadOf[item.index()] = (int) (item.sellerId() % threads);
                }
            }
            case RANGE -> {
                int n = items.size();
                for (Item item : items) {
                    threadOf[item.index()] = Math.min(threads - 1, item.index() * threads / n);
                }
            }
            case SIZE_GROUPED -> {
                Map<Long, Integer> countBySeller = new HashMap<>();
                for (Item item : items) {
                    countBySeller.merge(item.sellerId(), 1, Integer::sum);
                }
                List<Map.Entry<Long, Integer>> sellers = new ArrayList<>(countBySeller.entrySet());
                // LPT: 큰 판매자부터 지금 총량이 가장 작은 스레드에 배정한다.
                sellers.sort(Comparator.comparingInt((Map.Entry<Long, Integer> e) -> e.getValue()).reversed());
                long[] threadLoad = new long[threads];
                Map<Long, Integer> sellerToThread = new HashMap<>();
                for (Map.Entry<Long, Integer> e : sellers) {
                    int lightest = 0;
                    for (int t = 1; t < threads; t++) {
                        if (threadLoad[t] < threadLoad[lightest]) {
                            lightest = t;
                        }
                    }
                    sellerToThread.put(e.getKey(), lightest);
                    threadLoad[lightest] += e.getValue();
                }
                for (Item item : items) {
                    threadOf[item.index()] = sellerToThread.get(item.sellerId());
                }
            }
        }
        return threadOf;
    }
}
