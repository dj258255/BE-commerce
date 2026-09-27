package com.beomsu.becommerce.order.recovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 배치 워커 두 대: 리스 리더 대 행 단위 나눠 갖기(27절⑤, AWS Builders' Library "리더 선출") — 워커 한 대가
 * 죽을 때 두 방식의 공백 시간 대 중복 처리 건수.
 *
 * <p>{@link CheckoutRecoveryService} 같은 스캔형 배치를 워커 두 대로 돌린다고 가정한다.
 * <ul>
 *   <li><b>LEASE</b> — 리더만 행을 처리하고 대기 워커는 아무 것도 안 한다. 리더가 죽으면 리스가
 *       만료될 때까지 전체가 멈춘다. 한 번에 한 워커만 행을 만지므로 중복은 구조적으로 없다.</li>
 *   <li><b>ROW-CLAIM</b> — 두 워커가 항상 동시에 행을 원자적으로 점유해 처리한다(SQS 가시성
 *       타임아웃과 같은 개념). 한 워커가 죽어도 다른 워커는 자기 몫을 계속 처리해 전체가 멈추지
 *       않는다. 대신 점유 시효보다 처리가 오래 걸리는 행은 아직 살아서 처리 중이어도 다른 워커가
 *       다시 집어 이중 처리할 수 있다.</li>
 * </ul>
 *
 * <p>{@code HedgedRequestExperimentTest}(#416)와 같은 순수 JUnit 실험(Docker 불필요)이다. 워커 A는
 * 두 조건 모두 같은 시각에 실행기를 강제 종료해 "죽는다"(진행 중이던 행은 완료로 기록되지 않는다 —
 * 처리가 트랜잭션처럼 전부 되거나 전부 안 되거나라고 가정한다).
 *
 * <p>{@code ./gradlew experimentTest --tests '*BatchWorkerFailover*'}로 실행한다.
 */
@Tag("experiment")
class BatchWorkerFailoverExperimentTest {

    private static final int ROWS = 150;
    private static final long FAST_MS = 30;
    private static final long SLOW_MS = 500;
    private static final double SLOW_RATE = 0.08;
    private static final long VISIBILITY_TIMEOUT_MS = 300; // LEASE TTL 이자 ROW-CLAIM 가시성 타임아웃
    private static final long POLL_INTERVAL_MS = 20;
    private static final long CRASH_AT_MS = 400;
    private static final int WORKER_SLOTS = 3;
    private static final long RUN_TIMEOUT_SECONDS = 20;

    @Test
    @DisplayName("워커 A가 죽을 때 LEASE 대 ROW-CLAIM의 공백 시간·중복 처리 건수를 비교한다")
    void compareLeaseVsRowClaimOnWorkerCrash() throws Exception {
        Result lease = runLease();
        Result rowClaim = runRowClaim();

        System.out.printf("BATCH-FAILOVER cond=%s completed=%d/%d crash_gap_ms=%d duplicate_rows=%d total_ms=%d%n",
                lease.name, lease.completed, ROWS, lease.crashGapMs, lease.duplicateRows, lease.totalMs);
        System.out.printf("BATCH-FAILOVER cond=%s completed=%d/%d crash_gap_ms=%d duplicate_rows=%d total_ms=%d%n",
                rowClaim.name, rowClaim.completed, ROWS, rowClaim.crashGapMs, rowClaim.duplicateRows, rowClaim.totalMs);

        // 판정 기준(이슈 #420, 측정 전에 적음) — 조건이 실제로 섰다는 증거부터 확인한다.
        assertThat(lease.completed).isEqualTo(ROWS);      // 유실 0 (새 리더가 미완료 행을 재스캔)
        assertThat(rowClaim.completed).isEqualTo(ROWS);
        assertThat(lease.duplicateRows).isEqualTo(0);      // 2. LEASE 는 구조적으로 중복이 없다
        assertThat(rowClaim.duplicateRows).isGreaterThan(0); // 4. ROW-CLAIM 은 느린 행에서 중복이 생긴다
        assertThat(lease.crashGapMs).isGreaterThan(rowClaim.crashGapMs); // 1·3. LEASE 공백이 훨씬 크다
    }

    private record Result(String name, int completed, long crashGapMs, int duplicateRows, long totalMs) {
    }

    /** 행 하나가 끝난 사건 — 언제, 어느 행, 어느 워커가 끝냈는지. */
    private record Completion(long tsMs, int rowIndex, String worker) {
    }

    /** 두 조건이 공유하는 행 상태 — 완료 여부·실행 횟수·(ROW-CLAIM 전용) 점유자·점유 시각. */
    private static final class RowState {
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicInteger execCount = new AtomicInteger(0);
        final AtomicReference<String> claimedBy = new AtomicReference<>(null);
        final AtomicLong claimedAtMs = new AtomicLong(-1);
        /** LEASE 전용 — 한 리더 세션 안에서 슬롯 스레드끼리(같은 워커 안) 같은 행을 집지 않게 막는다. */
        final AtomicBoolean claimedByLeaderSession = new AtomicBoolean(false);
    }

    private static long process(boolean slowRoll) throws InterruptedException {
        long delay = slowRoll ? SLOW_MS : FAST_MS;
        Thread.sleep(delay);
        return delay;
    }

    // --- LEASE ---

    private Result runLease() throws Exception {
        long start = System.nanoTime();
        RowState[] rows = new RowState[ROWS];
        for (int i = 0; i < ROWS; i++) {
            rows[i] = new RowState();
        }
        List<Completion> completions = Collections.synchronizedList(new ArrayList<>()); // {tsMs, rowIndex}
        // A 를 처음부터 리더로 세팅한다 — 이 실험은 "누가 처음 리더가 되는가"가 아니라 "리더가 죽은
        // 뒤 무슨 일이 있는가"를 재므로, 시작 시점의 리더 선출 경합은 만들지 않는다.
        AtomicReference<String> leaderId = new AtomicReference<>("A");
        AtomicLong leaseExpiresAtMs = new AtomicLong(VISIBILITY_TIMEOUT_MS);
        Object leaseLock = new Object();
        AtomicReference<ExecutorService> leaderExecutor = new AtomicReference<>();
        ExecutorService initialExec = Executors.newFixedThreadPool(WORKER_SLOTS);
        leaderExecutor.set(initialExec);
        for (int slot = 0; slot < WORKER_SLOTS; slot++) {
            initialExec.submit(() -> leaderWorkerLoop(rows, "A", completions, start));
        }

        Runnable controlA = () -> {
            while (elapsedMs(start) < CRASH_AT_MS && !allDone(rows)) {
                tickLease(rows, "A", leaderId, leaseExpiresAtMs, leaseLock, leaderExecutor, completions, start);
                sleepQuiet(POLL_INTERVAL_MS);
            }
            // A 는 여기서 죽는다 — 진행 중이던 실행기를 강제 종료하고 더는 아무 것도 안 한다.
            ExecutorService mine = leaderExecutor.get();
            if (mine != null && "A".equals(leaderId.get())) {
                mine.shutdownNow();
            }
        };
        Runnable controlB = () -> {
            while (!allDone(rows) && elapsedMs(start) < TimeUnit.SECONDS.toMillis(RUN_TIMEOUT_SECONDS)) {
                tickLease(rows, "B", leaderId, leaseExpiresAtMs, leaseLock, leaderExecutor, completions, start);
                sleepQuiet(POLL_INTERVAL_MS);
            }
        };

        Thread tA = new Thread(controlA, "lease-A");
        Thread tB = new Thread(controlB, "lease-B");
        tA.start();
        tB.start();
        waitAllDone(rows, RUN_TIMEOUT_SECONDS);
        tA.join(2_000);
        tB.join(2_000);
        ExecutorService finalExec = leaderExecutor.get();
        if (finalExec != null) {
            finalExec.shutdownNow();
        }

        return summarize("LEASE", rows, completions, "A");
    }

    /** 리스 상태를 한 번 점검하고, 필요하면 리더가 되거나 리스를 갱신한다. */
    private void tickLease(RowState[] rows, String me, AtomicReference<String> leaderId, AtomicLong leaseExpiresAtMs,
                            Object leaseLock, AtomicReference<ExecutorService> leaderExecutor,
                            List<Completion> completions, long start) {
        long now = elapsedMs(start);
        boolean becameLeader = false;
        synchronized (leaseLock) {
            String current = leaderId.get();
            if (current == null || now > leaseExpiresAtMs.get()) {
                leaderId.set(me);
                leaseExpiresAtMs.set(now + VISIBILITY_TIMEOUT_MS);
                becameLeader = !me.equals(current);
            } else if (current.equals(me)) {
                leaseExpiresAtMs.set(now + VISIBILITY_TIMEOUT_MS); // 하트비트
            }
        }
        if (becameLeader) {
            // 이전 리더가 죽으며 남긴 "집었지만 못 끝낸" 표시를 지운다 — 새 리더 세션이 다시 집을 수 있게.
            for (RowState row : rows) {
                if (!row.done.get()) {
                    row.claimedByLeaderSession.set(false);
                }
            }
            ExecutorService exec = Executors.newFixedThreadPool(WORKER_SLOTS);
            leaderExecutor.set(exec);
            for (int slot = 0; slot < WORKER_SLOTS; slot++) {
                exec.submit(() -> leaderWorkerLoop(rows, me, completions, start));
            }
        }
    }

    /** 리더 세션 동안 미완료 행을 계속 찾아 처리한다(단일 소유자라 점유 표시가 필요 없다). */
    private void leaderWorkerLoop(RowState[] rows, String me, List<Completion> completions, long start) {
        while (true) {
            RowState target = null;
            int targetIdx = -1;
            for (int i = 0; i < rows.length; i++) {
                RowState row = rows[i];
                if (!row.done.get() && row.claimedByLeaderSession.compareAndSet(false, true)) {
                    target = row;
                    targetIdx = i;
                    break;
                }
            }
            if (target == null) {
                if (allDone(rows)) {
                    return; // 더 처리할 행이 없다
                }
                // 남은 미완료 행은 전부 같은 세션의 다른 슬롯이 처리 중 — 끝나거나 죽을 때까지 기다린다.
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return; // 죽는 중
                }
                continue;
            }
            try {
                boolean slow = ThreadLocalRandom.current().nextDouble() < SLOW_RATE;
                process(slow);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // 죽는 중 — 이 행은 미완료로 남는다(다음 리더가 재스캔한다)
            }
            target.execCount.incrementAndGet();
            target.done.set(true);
            completions.add(new Completion(elapsedMs(start), targetIdx, me));
        }
    }

    // --- ROW-CLAIM ---

    private Result runRowClaim() throws Exception {
        long start = System.nanoTime();
        RowState[] rows = new RowState[ROWS];
        for (int i = 0; i < ROWS; i++) {
            rows[i] = new RowState();
        }
        List<Completion> completions = Collections.synchronizedList(new ArrayList<>());

        ExecutorService execA = Executors.newFixedThreadPool(WORKER_SLOTS);
        ExecutorService execB = Executors.newFixedThreadPool(WORKER_SLOTS);
        for (int slot = 0; slot < WORKER_SLOTS; slot++) {
            execA.submit(() -> claimWorkerLoop(rows, "A", completions, start));
            execB.submit(() -> claimWorkerLoop(rows, "B", completions, start));
        }

        // 워커 A 를 CRASH_AT_MS 에 죽인다 — 진행 중이던 슬롯을 강제 종료한다.
        Thread crasher = new Thread(() -> {
            long now;
            while ((now = elapsedMs(start)) < CRASH_AT_MS) {
                sleepQuiet(Math.min(5, CRASH_AT_MS - now));
            }
            execA.shutdownNow();
        }, "row-claim-crasher");
        crasher.start();

        waitAllDone(rows, RUN_TIMEOUT_SECONDS);
        crasher.join(2_000);
        execA.shutdownNow();
        execB.shutdownNow();

        return summarize("ROW-CLAIM", rows, completions, "A");
    }

    private void claimWorkerLoop(RowState[] rows, String me, List<Completion> completions, long start) {
        while (!Thread.currentThread().isInterrupted() && !allDone(rows)) {
            RowState target = null;
            int targetIdx = -1;
            long now = elapsedMs(start);
            for (int i = 0; i < rows.length; i++) {
                RowState row = rows[i];
                if (row.done.get()) {
                    continue;
                }
                synchronized (row) {
                    if (row.done.get()) {
                        continue;
                    }
                    String owner = row.claimedBy.get();
                    long claimedAt = row.claimedAtMs.get();
                    if (owner == null || (now - claimedAt) > VISIBILITY_TIMEOUT_MS) {
                        row.claimedBy.set(me);
                        row.claimedAtMs.set(now);
                        target = row;
                        targetIdx = i;
                        break;
                    }
                }
            }
            if (target == null) {
                sleepQuiet(POLL_INTERVAL_MS);
                continue;
            }
            try {
                boolean slow = ThreadLocalRandom.current().nextDouble() < SLOW_RATE;
                process(slow);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // 죽는 중 — 이 행은 미완료로 남는다(점유 시효가 지나면 다른 워커가 다시 집는다)
            }
            target.execCount.incrementAndGet();
            target.done.set(true);
            completions.add(new Completion(elapsedMs(start), targetIdx, me));
        }
    }

    // --- 공통 ---

    private static boolean allDone(RowState[] rows) {
        for (RowState row : rows) {
            if (!row.done.get()) {
                return false;
            }
        }
        return true;
    }

    private static void waitAllDone(RowState[] rows, long timeoutSeconds) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (!allDone(rows) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static void sleepQuiet(long ms) {
        if (ms <= 0) {
            return;
        }
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @param crashedWorker 죽은 워커 id. 공백 시간은 <b>그 워커가 아닌 쪽</b>이 완료한 첫 행을 기준으로
     *                      잰다 — 죽은 워커가 강제 종료 직전에 우연히 경계 근처에서 끝낸 마지막 한두
     *                      건이 "공백이 거의 없었다"는 착시를 만드는 것을 막는다.
     */
    private Result summarize(String name, RowState[] rows, List<Completion> completions, String crashedWorker) {
        int completed = 0;
        int duplicateRows = 0;
        for (RowState row : rows) {
            if (row.done.get()) {
                completed++;
            }
            if (row.execCount.get() > 1) {
                duplicateRows++;
            }
        }
        List<Completion> sorted = new ArrayList<>(completions);
        sorted.sort((a, b) -> Long.compare(a.tsMs(), b.tsMs()));
        long firstAfterCrashByOther = -1;
        for (Completion c : sorted) {
            if (c.tsMs() > CRASH_AT_MS && !c.worker().equals(crashedWorker)) {
                firstAfterCrashByOther = c.tsMs();
                break;
            }
        }
        long crashGapMs = firstAfterCrashByOther >= 0 ? firstAfterCrashByOther - CRASH_AT_MS : -1;
        long totalMs = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1).tsMs();
        return new Result(name, completed, crashGapMs, duplicateRows, totalMs);
    }
}
