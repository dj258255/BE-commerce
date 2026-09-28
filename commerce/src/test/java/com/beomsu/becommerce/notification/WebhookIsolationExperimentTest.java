package com.beomsu.becommerce.notification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 판매자별 웹훅 발송 격리(27절⑩, AWS Builders' Library "Workload isolation using shuffle-sharding") —
 * 공유 풀 대 고정 샤드 대 셔플 샤딩(2/8) 대 셔플+재시도의 정상 판매자 발송 지연·기한 내 발송률.
 *
 * <p>지금 알림 모듈({@link LoggingNotificationSender})은 판매자 웹훅 발송기가 없다(로그로만 남긴다).
 * 이 실험은 발송기를 새로 만들지 않고 발송 워커 8개·판매자 200명(판매자 0 = 정상인 큰 판매자, 발송량의
 * 약 40%)을 순수 시뮬레이션으로 돌린다. 불량 판매자(무응답)의 느린 호출이 다른 정상 판매자의 발송을
 * 얼마나 지연시키는지가 이 실험의 핵심이다.
 *
 * <ul>
 *   <li><b>SHARED</b> — 워커 8개가 하나의 큐를 나눠 쓴다. 불량 판매자의 느린 호출이 어느 워커든 붙잡을
 *       수 있다.</li>
 *   <li><b>FIXED_SHARD</b> — 워커를 4샤드(2개씩)로 고정 분할한다. 판매자는 항상 같은 샤드로 간다.</li>
 *   <li><b>SHUFFLE</b> — 판매자마다 8개 중 2개를 조합(28가지 중 하나)으로 배정하고, 그 둘을 번갈아
 *       쓴다(맹목적 라운드로빈).</li>
 *   <li><b>SHUFFLE_RETRY</b> — 배정된 두 워커 중 제출 시점에 대기열이 더 짧은 쪽으로 보낸다. <b>실제
 *       취소·재제출이 아니라 제출 시점의 혼잡도 비교로 근사한다</b>(이슈 #430 "일부러 안 잴 것" 참고) —
 *       이미 시작된 불량 판매자 자신의 느린 호출은 이 방식으로도 빨라지지 않는다.</li>
 * </ul>
 *
 * <p>순수 JUnit 실험(Docker 불필요), {@code NotificationService}에 배선하지 않는다.
 *
 * <p>{@code ./gradlew experimentTest --tests '*WebhookIsolation*'}로 실행한다.
 */
@Tag("experiment")
class WebhookIsolationExperimentTest {

    private static final int WORKER_COUNT = 8;
    private static final int BIG_SELLER_ID = 0;
    private static final int OTHER_SELLER_COUNT = 199; // 1..199
    private static final int OTHER_SELLER_EVENTS = 3; // 큰 판매자 제외 판매자마다 이벤트 수
    private static final int BIG_SELLER_EVENTS = 398; // 전체의 약 40%(398 / (398+199*3) = 40.0%)
    private static final long FAST_MS = 20; // 정상 판매자 웹훅 응답
    private static final long SLOW_MS = 800; // 불량 판매자 무응답(원문 15초를 스케일 다운, 비율 보존)
    private static final long DEADLINE_MS = 1600; // 원문 30초를 같은 비율로 스케일 다운
    private static final int BAD_COUNT_20PCT = 40; // 불량 20%(199명 중, 원문 결정 기준의 앵커 값) — 관찰용(이슈 #430 보정 참고)
    private static final int BAD_COUNT_5PCT = 10; // 불량 5%(199명의 약 5%) — 관찰용
    private static final int BAD_COUNT_1 = 1; // 주 비교(이슈 #430 보정 결과, 워커 8개 규모에서 격리 효과가 뚜렷한 조건)
    // 이슈 #430 판정 기준의 "뚜렷이 높다/낮다"를 코드로 옮긴 여유폭. 보정 실측(SHARED 24.2%
    // 대 FIXED_SHARD 55.9%·SHUFFLE 51.7%·SHUFFLE_RETRY 58.6%, 차이 27~34퍼센트포인트)보다
    // 훨씬 보수적인 값을 골라 실행마다의 스레드 스케줄링 잡음에 강건하게 했다.
    private static final double CLEAR_MARGIN = 0.10;

    private enum Mode {SHARED, FIXED_SHARD, SHUFFLE, SHUFFLE_RETRY}

    private record Event(int sellerId, boolean slow) {
    }

    private record Result(String label, long p95GoodMs, double onTimeRateGood, long p95BigMs) {
    }

    @Test
    @DisplayName("불량 판매자 비율별 공유 풀·고정 샤드·셔플 샤딩·셔플+재시도의 정상 판매자 발송 지연·기한 내 발송률을 비교한다")
    void compareIsolationModes() throws Exception {
        Set<Integer> bad20pct = pickBadSellers(BAD_COUNT_20PCT);
        Set<Integer> bad5pct = pickBadSellers(BAD_COUNT_5PCT);
        Set<Integer> bad1 = pickBadSellers(BAD_COUNT_1);

        Map<String, Result> results = new LinkedHashMap<>();
        for (Mode mode : Mode.values()) {
            results.put(mode + "/bad20%(관찰)", runOnce(mode, bad20pct));
        }
        for (Mode mode : Mode.values()) {
            results.put(mode + "/bad5%(관찰)", runOnce(mode, bad5pct));
        }
        for (Mode mode : Mode.values()) {
            results.put(mode + "/bad1(주비교)", runOnce(mode, bad1));
        }

        for (Map.Entry<String, Result> e : results.entrySet()) {
            Result r = e.getValue();
            System.out.printf(
                    "WEBHOOK-ISOLATION cond=%s p95_good_ms=%d on_time_rate_good=%.3f p95_big_ms=%d(참고)%n",
                    e.getKey(), r.p95GoodMs, r.onTimeRateGood, r.p95BigMs);
        }

        double shared1 = results.get("SHARED/bad1(주비교)").onTimeRateGood();
        double fixed1 = results.get("FIXED_SHARD/bad1(주비교)").onTimeRateGood();
        double shuffle1 = results.get("SHUFFLE/bad1(주비교)").onTimeRateGood();
        double shuffleRetry1 = results.get("SHUFFLE_RETRY/bad1(주비교)").onTimeRateGood();

        // 이슈 #430 판정 기준 1: SHARED은 불량 1명에서도 정상 판매자 기한 내 발송률이 낮다(50% 미만).
        assertThat(shared1).isLessThan(0.50);

        // 이슈 #430 판정 기준 2: FIXED_SHARD·SHUFFLE·SHUFFLE_RETRY 모두 불량 1명에서 SHARED보다 뚜렷이 높다.
        assertThat(fixed1).isGreaterThan(shared1 + CLEAR_MARGIN);
        assertThat(shuffle1).isGreaterThan(shared1 + CLEAR_MARGIN);
        assertThat(shuffleRetry1).isGreaterThan(shared1 + CLEAR_MARGIN);

        // 이슈 #430 판정 기준 3: SHUFFLE_RETRY가 불량 1명에서 네 방식 중 가장 높다.
        assertThat(shuffleRetry1).isGreaterThanOrEqualTo(fixed1);
        assertThat(shuffleRetry1).isGreaterThanOrEqualTo(shuffle1);
        assertThat(shuffleRetry1).isGreaterThanOrEqualTo(shared1);

        // 판정 기준 4·5(관찰, 판정 아님)는 위 print 출력을 PR 본문에 그대로 옮겨 근거로 남긴다.
    }

    /** 판매자 1..199 중 무작위로 고른다(고정 시드) — 연속된 id를 그대로 쓰면 고정 샤드의 나머지 연산과
     * 우연히 맞아떨어져 불량 판매자가 샤드에 균등하게 퍼지는 비현실적인 최선의 경우가 된다. */
    private Set<Integer> pickBadSellers(int count) {
        List<Integer> pool = new ArrayList<>();
        for (int i = 1; i <= OTHER_SELLER_COUNT; i++) {
            pool.add(i);
        }
        Collections.shuffle(pool, new Random(42));
        return new TreeSet<>(pool.subList(0, count));
    }

    private List<Event> buildEvents(Set<Integer> badSellers) {
        List<Event> events = new ArrayList<>();
        for (int i = 0; i < BIG_SELLER_EVENTS; i++) {
            events.add(new Event(BIG_SELLER_ID, false)); // 큰 판매자는 정상 응답
        }
        for (int sellerId = 1; sellerId <= OTHER_SELLER_COUNT; sellerId++) {
            boolean slow = badSellers.contains(sellerId);
            for (int i = 0; i < OTHER_SELLER_EVENTS; i++) {
                events.add(new Event(sellerId, slow));
            }
        }
        return events;
    }

    private Result runOnce(Mode mode, Set<Integer> badSellers) throws Exception {
        List<Event> events = buildEvents(badSellers);
        ThreadPoolExecutor[] workers = new ThreadPoolExecutor[WORKER_COUNT];
        List<ExecutorService> poolsToShutdown = new ArrayList<>();
        try {
            for (int i = 0; i < WORKER_COUNT; i++) {
                workers[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
                poolsToShutdown.add(workers[i]);
            }
            int[][] shufflePairs = allPairs(WORKER_COUNT); // 28개, index = sellerId % 28

            long start = System.nanoTime();
            List<long[]> latencies = Collections.synchronizedList(new ArrayList<>()); // {sellerId, latencyMs}
            CountDownLatch done = new CountDownLatch(events.size());

            for (Event event : events) {
                int primary = choosePrimaryWorker(mode, event.sellerId(), shufflePairs, workers);
                workers[primary].submit(() -> {
                    try {
                        Thread.sleep(event.slow() ? SLOW_MS : FAST_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        latencies.add(new long[]{event.sellerId(), elapsedMs(start)});
                        done.countDown();
                    }
                });
            }
            assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();

            return summarize(mode.name(), latencies, badSellers);
        } finally {
            for (ExecutorService pool : poolsToShutdown) {
                pool.shutdownNow();
            }
        }
    }

    /** SHARED·FIXED_SHARD는 라우팅 로직이 필요 없다(풀 자체가 공유·고정이다) — 워커 배열을 그 구조로
     * 쪼개 쓰는 대신, 여기서는 8개 개별 워커에 대해 같은 효과를 내는 선택 규칙으로 통일해 구현한다. */
    private int choosePrimaryWorker(Mode mode, int sellerId, int[][] shufflePairs, ThreadPoolExecutor[] workers) {
        switch (mode) {
            case SHARED -> {
                // 공유 풀: 8개 모두 후보 — 대기열이 가장 짧은 워커로 보낸다(하나의 큐를 8스레드가
                // 나눠 쓰는 것과 같은 효과 — 어떤 워커든 다음으로 비는 쪽이 받는다).
                return leastBusy(workers, range(WORKER_COUNT));
            }
            case FIXED_SHARD -> {
                int shard = sellerId % 4; // 4샤드, 샤드마다 워커 2개(2*shard, 2*shard+1)
                return leastBusy(workers, new int[]{2 * shard, 2 * shard + 1});
            }
            case SHUFFLE -> {
                int[] pair = shufflePairs[sellerId % shufflePairs.length];
                // 맹목적 라운드로빈 — 이벤트 순서로 번갈아 쓴다(호출 횟수의 홀짝으로 근사).
                return sellerCallCounter(sellerId) % 2 == 0 ? pair[0] : pair[1];
            }
            case SHUFFLE_RETRY -> {
                int[] pair = shufflePairs[sellerId % shufflePairs.length];
                return leastBusy(workers, pair);
            }
            default -> throw new IllegalStateException();
        }
    }

    private final Map<Integer, Integer> callCounters = new java.util.concurrent.ConcurrentHashMap<>();

    private int sellerCallCounter(int sellerId) {
        return callCounters.merge(sellerId, 1, Integer::sum);
    }

    private static int[] range(int n) {
        int[] r = new int[n];
        for (int i = 0; i < n; i++) {
            r[i] = i;
        }
        return r;
    }

    private static int leastBusy(ThreadPoolExecutor[] workers, int[] candidates) {
        int best = candidates[0];
        long bestLoad = workers[best].getQueue().size() + workers[best].getActiveCount();
        for (int i = 1; i < candidates.length; i++) {
            int c = candidates[i];
            long load = workers[c].getQueue().size() + workers[c].getActiveCount();
            if (load < bestLoad) {
                bestLoad = load;
                best = c;
            }
        }
        return best;
    }

    /** {@code n}개 중 2개를 고르는 모든 조합(순서 없음). n=8이면 28가지. */
    private static int[][] allPairs(int n) {
        List<int[]> pairs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                pairs.add(new int[]{i, j});
            }
        }
        return pairs.toArray(new int[0][]);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private Result summarize(String label, List<long[]> latencies, Set<Integer> badSellers) {
        List<Long> good = new ArrayList<>();
        List<Long> big = new ArrayList<>();
        for (long[] e : latencies) {
            int sellerId = (int) e[0];
            long latency = e[1];
            if (sellerId == BIG_SELLER_ID) {
                big.add(latency);
            } else if (!badSellers.contains(sellerId)) {
                good.add(latency);
            }
        }
        Collections.sort(good);
        Collections.sort(big);
        long onTime = good.stream().filter(l -> l <= DEADLINE_MS).count();
        double onTimeRate = good.isEmpty() ? 1.0 : (double) onTime / good.size();
        return new Result(label, percentile(good, 0.95), onTimeRate, percentile(big, 0.95));
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
