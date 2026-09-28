package com.beomsu.becommerce.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 재시작 직후 웜업(27절⑥, Google SRE Book 캐스케이딩 장애) — 트래픽을 한 번에 대 서서히 돌릴 때의
 * p95 · 타임아웃(2차 장애) · 회복 시간.
 *
 * <p>이 저장소는 이미 ADR-022 실측에서 "워밍업이 없으면 결론이 뒤집힌다"를 확인했다 — 기동 직후 첫
 * 요청 p95 가 903ms, 워밍업 뒤 같은 설정이 99.5ms(약 9배)였다. 이 실험은 그 콜드/웜 비율을 시간에
 * 따라 지수적으로 감쇠하는 배율로 근사해, 재시작 직후 트래픽 투입 방식(즉시 전량 대 램프)의 대가를
 * 비교한다. 처리 용량은 ADR-022 와 같은 값(Hikari `maximum-pool-size: 20`)을 그대로 쓴다.
 *
 * <ul>
 *   <li><b>IMMEDIATE</b> — 재시작 직후 t=0 부터 목표 처리율을 그대로 투입한다.</li>
 *   <li><b>GRADUAL</b> — t=0 부터 2초에 걸쳐 0 에서 목표 처리율까지 선형으로 늘린다.</li>
 * </ul>
 *
 * <p>{@code HedgedRequestExperimentTest}(#416)와 같은 순수 JUnit 실험(Docker 불필요, 실제 스프링
 * 앱·JIT·커넥션 풀은 재현하지 않는 시뮬레이션)이다.
 *
 * <p>{@code ./gradlew experimentTest --tests '*RestartWarmup*'}로 실행한다.
 */
@Tag("experiment")
class RestartWarmupExperimentTest {

    private static final int CAPACITY = 20; // ADR-022 Hikari maximum-pool-size 와 동일
    private static final long BASE_LATENCY_MS = 20; // 완전히 웜인 상태의 처리 시간
    private static final double COLD_START_MULTIPLIER = 8.0; // ADR-022 실측 콜드/웜 비율(약 9배)에 근거
    private static final double WARMUP_DECAY_MS = 3000.0; // 감쇠 시정수(실제 JIT 단계별 컴파일은 수 초 걸린다)
    private static final long TIMEOUT_MS = 600; // 이보다 오래 기다리면 2차 장애(타임아웃)로 센다
    private static final long TOTAL_WINDOW_MS = 10000;
    private static final double TARGET_RATE_PER_SEC = 300.0; // 콜드 상태 용량(약 125/s)을 크게 넘는 목표치
    private static final long RAMP_MS = 4000;
    private static final long CONGESTION_WAIT_THRESHOLD_MS = 15; // 이보다 오래 용량을 기다리면 "혼잡 중"

    @Test
    @DisplayName("재시작 직후 IMMEDIATE 대 GRADUAL 의 p95·타임아웃·회복 시간을 비교한다")
    void gradualRampAvoidsColdStartOverload() throws Exception {
        Result immediate = run("IMMEDIATE", false);
        Result gradual = run("GRADUAL", true);

        System.out.printf("RESTART-WARMUP cond=%s completed=%d timeouts=%d p50=%dms p95=%dms recovery_ms=%d%n",
                immediate.name, immediate.completed, immediate.timeouts, immediate.p50, immediate.p95,
                immediate.recoveryMs);
        System.out.printf("RESTART-WARMUP cond=%s completed=%d timeouts=%d p50=%dms p95=%dms recovery_ms=%d%n",
                gradual.name, gradual.completed, gradual.timeouts, gradual.p50, gradual.p95, gradual.recoveryMs);

        // 판정 기준(이슈 #422, 측정 전에 적음) — 조건이 실제로 섰다는 증거부터 확인한다.
        assertThat(immediate.timeouts).isGreaterThan(0);          // 1. IMMEDIATE 는 2차 장애가 실제로 생긴다
        assertThat(gradual.timeouts).isLessThan(immediate.timeouts); // 2. GRADUAL 은 뚜렷이 적다
        assertThat(immediate.recoveryMs).isGreaterThan(gradual.recoveryMs); // 3. IMMEDIATE 회복이 더 오래 걸린다
        // 4. 둘 다 결국 비슷한 자리수로 일했다 — GRADUAL 이 그냥 덜 일한 게 아니다.
        assertThat((double) gradual.completed).isCloseTo(immediate.completed,
                org.assertj.core.data.Percentage.withPercentage(20));
    }

    private record Result(String name, int completed, int timeouts, long p50, long p95, long recoveryMs) {
    }

    private Result run(String name, boolean gradual) throws Exception {
        long start = System.nanoTime();
        Semaphore capacity = new Semaphore(CAPACITY, true);
        AtomicInteger timeouts = new AtomicInteger();
        List<long[]> latencyEvents = Collections.synchronizedList(new ArrayList<>()); // {completedAtMs, latencyMs}
        ExecutorService requestPool = Executors.newCachedThreadPool();

        double carry = 0; // 틱마다 누적되는 소수 요청 수(반올림 손실 방지)
        long tick = 20; // ms
        for (long now = 0; now < TOTAL_WINDOW_MS; now += tick) {
            double rate = gradual
                    ? TARGET_RATE_PER_SEC * Math.min(1.0, now / (double) RAMP_MS)
                    : TARGET_RATE_PER_SEC;
            carry += rate * (tick / 1000.0);
            int toSubmit = (int) Math.floor(carry);
            carry -= toSubmit;
            for (int i = 0; i < toSubmit; i++) {
                requestPool.submit(() -> handleRequest(start, capacity, timeouts, latencyEvents));
            }
            Thread.sleep(tick);
        }
        requestPool.shutdown();
        assertThat(requestPool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        return summarize(name, latencyEvents, timeouts.get());
    }

    private void handleRequest(long start, Semaphore capacity, AtomicInteger timeouts, List<long[]> latencyEvents) {
        long arrivalMs = elapsedMs(start);
        boolean acquired;
        try {
            acquired = capacity.tryAcquire(TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        long acquiredAtMs = elapsedMs(start);
        if (!acquired) {
            timeouts.incrementAndGet();
            return;
        }
        try {
            long execMs = elapsedMs(start);
            long delay = (long) (BASE_LATENCY_MS * coldMultiplier(execMs));
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } finally {
            capacity.release();
        }
        long completedAtMs = elapsedMs(start);
        long latency = completedAtMs - arrivalMs;
        long waitMs = acquiredAtMs - arrivalMs; // 용량(세마포어)을 기다린 시간 — 콜드 배율과는 독립된 혼잡 신호
        if (latency > TIMEOUT_MS) {
            timeouts.incrementAndGet(); // 처리는 됐지만 클라이언트 기준 타임아웃을 넘겼다
            return;
        }
        latencyEvents.add(new long[]{completedAtMs, latency, waitMs});
    }

    /** 콜드스타트 배율 — ADR-022 관측 비율(약 9배)에서 시작해 지수적으로 1배(웜)로 감쇠한다. */
    private static double coldMultiplier(long elapsedMs) {
        return 1.0 + (COLD_START_MULTIPLIER - 1.0) * Math.exp(-elapsedMs / WARMUP_DECAY_MS);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * 회복 시간은 <b>세마포어(용량)를 기다린 시간</b>이 뚜렷한(임계값 이상) 마지막 완료 시각으로 잰다.
     * 원시 지연(latency)에는 콜드 배율 자체(트래픽 방식과 무관하게 시간에 따라 똑같이 감쇠)가 섞여
     * 있어, "혼잡이 실제로 언제 풀렸는가"를 보려면 대기 시간만 따로 봐야 한다 — 1차 구현은 100ms 창별
     * 원시 지연 p95 로 재다가, 콜드 배율 감쇠 자체가 두 조건 모두를 지배해 큐잉 효과가 가려졌다.
     */
    private Result summarize(String name, List<long[]> latencyEvents, int timeouts) {
        List<long[]> sorted = new ArrayList<>(latencyEvents);
        sorted.sort((a, b) -> Long.compare(a[0], b[0]));
        List<Long> allLatencies = new ArrayList<>();
        long recoveryMs = 0;
        for (long[] e : sorted) {
            allLatencies.add(e[1]);
            long completedAtMs = e[0];
            long waitMs = e[2];
            if (waitMs > CONGESTION_WAIT_THRESHOLD_MS) {
                recoveryMs = completedAtMs;
            }
        }
        List<Long> sortedLatencies = new ArrayList<>(allLatencies);
        Collections.sort(sortedLatencies);

        return new Result(name, allLatencies.size(), timeouts,
                percentile(sortedLatencies, 0.50), percentile(sortedLatencies, 0.95), recoveryMs);
    }

    private static long percentile(List<Long> sorted, double p) {
        if (sorted.isEmpty()) {
            return -1;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
