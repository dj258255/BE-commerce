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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 여러 대 실시간 알림 전파(27절⑪, 이슈 #431, 배달의민족 주문접수 SSE 전환) — 전체 방송 대 세션 위치
 * 라우팅의 메시지 수·전달 지연 비교.
 *
 * <p>지금 알림 모듈에는 SSE 서버가 없다. 이 실험은 SSE 서버·Redis 세션 레지스트리를 새로 만들지 않고,
 * 인스턴스 여러 대(2·4·8)에 흩어진 세션 5,000개로 주문 상태 이벤트 3,000건을 전달하는 두 방식을 순수
 * 시뮬레이션으로 돌린다.
 *
 * <ul>
 *   <li><b>BROADCAST</b> — 이벤트마다 모든 인스턴스에 보낸다. 인스턴스는 자기 세션인지 걸러보고(필터
 *       비용), 맞으면 실제 전달(전달 비용)한다. 인스턴스가 늘어도 각 인스턴스가 받는 이벤트 수(필터해야
 *       할 양)는 줄지 않는다.</li>
 *   <li><b>ROUTED</b> — 이벤트마다 레지스트리(Redis 세션 위치 조회를 근사)에서 소유 인스턴스를 찾아 그
 *       인스턴스에만 보낸다. 조회 지연이 이벤트마다 고정 비용으로 붙지만, 인스턴스당 처리량은 인스턴스
 *       수에 반비례로 준다.</li>
 * </ul>
 *
 * <p>순수 JUnit 실험(Docker 불필요), {@code NotificationService}·실제 SSE 엔드포인트에 배선하지 않는다.
 *
 * <p>{@code ./gradlew experimentTest --tests '*SseFanoutExperiment*'}로 실행한다.
 */
@Tag("experiment")
class SseFanoutExperimentTest {

    private static final int[] INSTANCE_COUNTS = {2, 4, 8};
    private static final int SESSION_COUNT = 5000; // 원문 "SSE 연결 5천"과 같은 규모(메모리상 정수라 스케일 다운 불필요)
    private static final int EVENT_COUNT = 3000; // 주문 상태 알림 이벤트
    private static final long FILTER_COST_MS = 1; // 인스턴스가 자기 세션인지 확인하는 비용
    private static final long DELIVERY_COST_MS = 3; // 실제 SSE write 근사
    private static final long REGISTRY_LOOKUP_MS = 2; // Redis 세션 위치 조회 지연 근사(기록된 지연에 더함, 스레드를 막지 않음)

    private enum Mode {BROADCAST, ROUTED}

    private record Result(String label, long totalMessages, long deliveredCount, long p95LatencyMs) {
    }

    @Test
    @DisplayName("인스턴스 수별 전체 방송 대 세션 위치 라우팅의 메시지 수·전달 지연을 비교한다")
    void compareFanoutModes() throws Exception {
        List<Integer> targetSessions = drawTargetSessions();

        Map<String, Result> results = new LinkedHashMap<>();
        for (int n : INSTANCE_COUNTS) {
            for (Mode mode : Mode.values()) {
                results.put(mode + "/n=" + n, runOnce(mode, n, targetSessions));
            }
        }

        for (Map.Entry<String, Result> e : results.entrySet()) {
            Result r = e.getValue();
            System.out.printf(
                    "SSE-FANOUT cond=%s total_messages=%d delivered=%d p95_latency_ms=%d%n",
                    e.getKey(), r.totalMessages, r.deliveredCount, r.p95LatencyMs);
        }

        // 판정 기준 1(이슈 #431): BROADCAST 총 메시지 수는 N배, ROUTED는 N과 무관하게 이벤트 수와 같다.
        for (int n : INSTANCE_COUNTS) {
            assertThat(results.get("BROADCAST/n=" + n).totalMessages()).isEqualTo((long) EVENT_COUNT * n);
            assertThat(results.get("ROUTED/n=" + n).totalMessages()).isEqualTo(EVENT_COUNT);
            // 실제로 전부 처리됐다는 증거(제출 건수 == 처리 건수는 runOnce 내부에서 이미 확인).
            assertThat(results.get("BROADCAST/n=" + n).deliveredCount()).isEqualTo(EVENT_COUNT);
            assertThat(results.get("ROUTED/n=" + n).deliveredCount()).isEqualTo(EVENT_COUNT);
        }

        // 판정 기준 3·4·5(이슈 #431, 보정 후): BROADCAST의 개선폭(N=2→N=8)이 ROUTED의 개선폭보다
        // 뚜렷이 작고(20퍼센트포인트 이상), ROUTED는 N이 늘수록 뚜렷이(40% 이상) 개선되며, 모든 N에서
        // ROUTED의 p95가 BROADCAST보다 낮다.
        long broadcastN2 = results.get("BROADCAST/n=2").p95LatencyMs();
        long broadcastN4 = results.get("BROADCAST/n=4").p95LatencyMs();
        long broadcastN8 = results.get("BROADCAST/n=8").p95LatencyMs();
        long routedN2 = results.get("ROUTED/n=2").p95LatencyMs();
        long routedN4 = results.get("ROUTED/n=4").p95LatencyMs();
        long routedN8 = results.get("ROUTED/n=8").p95LatencyMs();

        double broadcastImprovement = 1.0 - (double) broadcastN8 / broadcastN2;
        double routedImprovement = 1.0 - (double) routedN8 / routedN2;

        // 판정 기준 3: 개선폭 차이가 20퍼센트포인트 이상.
        assertThat(routedImprovement - broadcastImprovement).isGreaterThanOrEqualTo(0.20);

        // 판정 기준 4: ROUTED는 N이 늘수록 뚜렷이(40% 이상) 개선된다.
        assertThat((double) routedN4 / routedN2).isLessThanOrEqualTo(0.60);
        assertThat((double) routedN8 / routedN4).isLessThanOrEqualTo(0.60);

        // 판정 기준 5: 모든 N에서 ROUTED의 p95가 BROADCAST보다 낮다.
        assertThat(routedN2).isLessThan(broadcastN2);
        assertThat(routedN4).isLessThan(broadcastN4);
        assertThat(routedN8).isLessThan(broadcastN8);
    }

    private List<Integer> drawTargetSessions() {
        Random random = new Random(11);
        List<Integer> targets = new ArrayList<>(EVENT_COUNT);
        for (int i = 0; i < EVENT_COUNT; i++) {
            targets.add(random.nextInt(SESSION_COUNT));
        }
        return targets;
    }

    private Result runOnce(Mode mode, int instanceCount, List<Integer> targetSessions) throws Exception {
        ThreadPoolExecutor[] instances = new ThreadPoolExecutor[instanceCount];
        List<ExecutorService> poolsToShutdown = new ArrayList<>();
        try {
            for (int i = 0; i < instanceCount; i++) {
                instances[i] = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
                poolsToShutdown.add(instances[i]);
            }

            AtomicInteger submitted = new AtomicInteger();
            AtomicInteger processed = new AtomicInteger();
            AtomicInteger delivered = new AtomicInteger();
            List<Long> deliveryLatencies = Collections.synchronizedList(new ArrayList<>());

            long start = System.nanoTime();
            int totalTasks = mode == Mode.BROADCAST ? targetSessions.size() * instanceCount : targetSessions.size();
            CountDownLatch done = new CountDownLatch(totalTasks);

            if (mode == Mode.BROADCAST) {
                for (int sessionId : targetSessions) {
                    int owner = sessionId % instanceCount;
                    for (int i = 0; i < instanceCount; i++) {
                        boolean isOwner = i == owner;
                        submitted.incrementAndGet();
                        instances[i].submit(() -> {
                            try {
                                Thread.sleep(FILTER_COST_MS);
                                if (isOwner) {
                                    Thread.sleep(DELIVERY_COST_MS);
                                    delivered.incrementAndGet();
                                    deliveryLatencies.add(elapsedMs(start));
                                }
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            } finally {
                                processed.incrementAndGet();
                                done.countDown();
                            }
                        });
                    }
                }
            } else {
                for (int sessionId : targetSessions) {
                    int owner = sessionId % instanceCount;
                    submitted.incrementAndGet();
                    instances[owner].submit(() -> {
                        try {
                            Thread.sleep(DELIVERY_COST_MS);
                            delivered.incrementAndGet();
                            deliveryLatencies.add(elapsedMs(start) + REGISTRY_LOOKUP_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            processed.incrementAndGet();
                            done.countDown();
                        }
                    });
                }
            }

            assertThat(done.await(120, TimeUnit.SECONDS)).isTrue();
            // 제출 건수와 실제 처리 건수가 같다는 증거(0/누락 없이 조건이 섰다는 근거).
            assertThat(processed.get()).isEqualTo(submitted.get());

            List<Long> sorted = new ArrayList<>(deliveryLatencies);
            Collections.sort(sorted);
            long p95 = percentile(sorted, 0.95);
            return new Result(mode.name(), submitted.get(), delivered.get(), p95);
        } finally {
            for (ExecutorService pool : poolsToShutdown) {
                pool.shutdownNow();
            }
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
