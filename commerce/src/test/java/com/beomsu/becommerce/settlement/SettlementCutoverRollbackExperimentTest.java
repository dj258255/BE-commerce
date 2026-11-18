package com.beomsu.becommerce.settlement;

import com.beomsu.becommerce.testsupport.SharedContainers;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정산 분리를 되돌릴 때 무엇이 빠지나(#473). ADR-024 의 델타 예행과 같은 <b>DB 수준 예행</b>을 실제 MySQL 8.4 에서 한다.
 *
 * <p>스키마 셋: 옛 저장소(src), 새 저장소(dst), 둘과 독립인 이벤트 로그(log, 결제 이벤트 토픽 역할). 모든 조작은 로그에 먼저
 * 남기고 그때 쓰기 주인 저장소에 반영한다. 진실은 로그를 처음부터 다시 적용한 상태다. 쓰기 스레드마다 맡는 결제 번호를
 * 나눠(결제 번호 % 스레드 수) 한 결제의 조작이 로그 순서대로 반영되게 한다.
 *
 * <p>조작 셋: 승인(새 정산 항목, 미확정), 구매확정(미확정 → 확정), 부분 취소(금액 100 감소, 취소 순번 + 1).
 *
 * <p>결과는 {@code CUTOVER-*} 한 줄씩 콘솔에 남긴다. {@code ./gradlew experimentTest --tests '*SettlementCutoverRollback*'}
 */
@Tag("experiment")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SettlementCutoverRollbackExperimentTest {

    private static final int WRITERS = 8;
    private static final int SEED_ITEMS = 10_000;
    private static final long FORWARD_MS = 10_000;
    private static final long AFTER_ROLLBACK_MS = 2_000;
    private static final long CANCEL_DELTA = 100;

    private static HikariDataSource src;
    private static HikariDataSource dst;
    private static HikariDataSource log;
    private static final AtomicBoolean HARNESS_OK = new AtomicBoolean(true);

    enum Mode {
        NONE(false, false, false),
        FREEZE_COPYBACK(false, false, false),
        REVERSE_SYNC(true, true, true),
        REVERSE_SYNC_GUARD_ONLY(true, false, true),
        REVERSE_SYNC_UNSAFE(true, false, false),
        REPLAY(false, false, false),
        REPLAY_BY_TOPIC(false, false, false),
        REPLAY_BY_TOPIC_DEFER(false, false, false);

        final boolean reverse;
        final boolean drain;
        final boolean guard;

        Mode(boolean reverse, boolean drain, boolean guard) {
            this.reverse = reverse;
            this.drain = drain;
            this.guard = guard;
        }
    }

    enum Op { APPROVE, CONFIRM, CANCEL }

    @BeforeAll
    static void start() {
        src = pool(SharedContainers.freshDatabase("cut_src"));
        dst = pool(SharedContainers.freshDatabase("cut_dst"));
        log = pool(SharedContainers.freshDatabase("cut_log"));
    }

    @AfterAll
    static void stop() {
        for (HikariDataSource d : new HikariDataSource[]{src, dst, log}) if (d != null) d.close();
    }

    private static HikariDataSource pool(String url) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(SharedContainers.username());
        cfg.setPassword(SharedContainers.password());
        cfg.setMaximumPoolSize(30);
        cfg.setMinimumIdle(4);
        return new HikariDataSource(cfg);
    }

    // =====================================================================================================

    @Test
    @Order(1)
    @DisplayName("버그 없는 경우: 되돌리기 방식별 어긋남과 쓰기 정지 시간")
    void rollbackWithoutBug() throws Exception {
        for (Mode m : Mode.values()) {
            Result r = new Run(m, false).execute();
            r.print("clean");
        }
        assertThat(HARNESS_OK.get()).as("NONE 의 어긋남이 전환 뒤 닿은 항목 수와 같아야 한다(판정 1)").isTrue();
    }

    @Test
    @Order(2)
    @DisplayName("새 저장소 버그로 되돌리는 경우: 되돌린 뒤 옛 저장소에 남은 틀린 행")
    void rollbackBecauseOfDstBug() throws Exception {
        for (Mode m : new Mode[]{Mode.FREEZE_COPYBACK, Mode.REVERSE_SYNC, Mode.REPLAY, Mode.REPLAY_BY_TOPIC_DEFER}) {
            Result r = new Run(m, true).execute();
            r.print("dst_bug");
        }
    }

    @Test
    @Order(3)
    @DisplayName("사후 탐색: 역방향 복제 지연 0.5초에서 역방향 동기화 세 변형")
    void reverseSyncUnderReplicationLag() throws Exception {
        // 첫 실행에서 UNSAFE 가 어긋남 0 이었다. 로컬은 역방향 지연이 최대 90건 남짓이라 되돌린 뒤의 새 쓰기와 겹칠 일이
        // 드물었다. 그 0 이 안전의 증거인지 보려고 운영처럼 복제 지연을 넣는다(측정 전 이슈 #473 댓글에 예측을 적음)
        for (Mode m : new Mode[]{Mode.REVERSE_SYNC, Mode.REVERSE_SYNC_GUARD_ONLY, Mode.REVERSE_SYNC_UNSAFE}) {
            Result r = new Run(m, false, 500).execute();
            r.print("lag500");
        }
    }

    // =====================================================================================================

    record Result(Mode mode, int touchedForward, int missing, int stale, int extra, int wrongFromBug, int bugInjected,
                  double freezeMs, int maxReverseLag, long writesForward, long writesTotal, String recon) {
        void print(String label) {
            System.out.printf("CUTOVER-%s mode=%s touched_forward=%d missing=%d stale=%d extra=%d bug_injected=%d"
                            + " wrong_from_bug=%d freeze_ms=%.1f max_reverse_lag=%d writes_forward=%d writes_total=%d recon=%s%n",
                    label.toUpperCase(), mode, touchedForward, missing, stale, extra, bugInjected, wrongFromBug, freezeMs,
                    maxReverseLag, writesForward, writesTotal, recon);
        }
    }

    /** 결제 하나의 상태. 진실 계산과 쓰기 스레드의 선택에 쓴다. */
    static final class Item {
        long amount;
        String status;
        int lastCancelSeq;

        Item(long amount, String status, int lastCancelSeq) {
            this.amount = amount;
            this.status = status;
            this.lastCancelSeq = lastCancelSeq;
        }

        void apply(Op op, long value) {
            switch (op) {
                case APPROVE -> {
                    amount = value;
                    status = "PENDING_CONFIRMATION";
                    lastCancelSeq = -1;
                }
                case CONFIRM -> {
                    if (status.equals("PENDING_CONFIRMATION")) status = "CONFIRMED";
                }
                case CANCEL -> {
                    if (amount >= value) {
                        amount -= value;
                        lastCancelSeq++;
                    }
                }
            }
        }
    }

    final class Run {
        final Mode mode;
        final boolean dstBug;
        final long reverseDelayMs;
        int lagAtRollback;
        final ReentrantReadWriteLock gate = new ReentrantReadWriteLock();
        final AtomicReference<HikariDataSource> owner = new AtomicReference<>();
        final AtomicBoolean writing = new AtomicBoolean(true);
        final AtomicLong writes = new AtomicLong();
        final AtomicLong nextPid = new AtomicLong();
        final AtomicInteger dstCancels = new AtomicInteger();
        final Set<Long> bugged = ConcurrentHashMap.newKeySet();
        final AtomicLong firstBugAt = new AtomicLong();
        final AtomicInteger maxLag = new AtomicInteger();
        ReverseSync reverse;

        Run(Mode mode, boolean dstBug) {
            this(mode, dstBug, 0);
        }

        /** reverseDelayMs: 역방향 복제 지연. 변경이 이만큼 오래된 뒤에야 역방향 동기화에 보인다(저장소 사이 복제 지연 흉내). */
        Run(Mode mode, boolean dstBug, long reverseDelayMs) {
            this.mode = mode;
            this.dstBug = dstBug;
            this.reverseDelayMs = reverseDelayMs;
        }

        Result execute() throws Exception {
            resetSchemas();
            seed();
            owner.set(src);

            // 전환: 쓰기를 멈추고 미확정 항목을 dst 로 복사한 뒤 쓰기 주인을 dst 로
            long cutoverSeq;
            gate.writeLock().lock();
            try {
                copyAll(src, dst);
                cutoverSeq = maxLogSeq();
                owner.set(dst);
                if (mode.reverse) {
                    reverse = new ReverseSync(mode.guard);
                    reverse.start();
                }
            } finally {
                gate.writeLock().unlock();
            }

            ExecutorService pool = Executors.newFixedThreadPool(WRITERS);
            List<Future<?>> fs = new ArrayList<>();
            for (int w = 0; w < WRITERS; w++) {
                int writer = w;
                fs.add(pool.submit(() -> writerLoop(writer)));
            }

            Reconciler rec0 = dstBug ? new Reconciler(0) : null;
            Reconciler rec1 = dstBug ? new Reconciler(1_000) : null;
            if (dstBug) {
                rec0.start();
                rec1.start();
            }
            long forwardEnd = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(FORWARD_MS);
            while (System.nanoTime() < forwardEnd) {
                if (reverse != null) maxLag.accumulateAndGet(reverse.lag(), Math::max);
                Thread.sleep(100);
            }
            if (dstBug) {
                rec0.stop();
                rec1.stop();
            }

            // 되돌림
            long rollbackSeq;
            long writesForward;
            double freezeMs;
            long t0;
            gate.writeLock().lock();
            try {
                t0 = System.nanoTime();
                rollbackSeq = maxLogSeq();
                lagAtRollback = reverse == null ? 0 : reverse.lag();   // 되돌리는 순간 아직 옮기지 못한 변경
                writesForward = writes.get();
                switch (mode) {
                    case NONE, REVERSE_SYNC_GUARD_ONLY, REVERSE_SYNC_UNSAFE -> {
                        // 쓰기 방향만 돌린다. 역방향 동기화는 밀린 것을 계속 복사한다
                    }
                    case FREEZE_COPYBACK -> copyChangedSince(dst, src);
                    case REVERSE_SYNC -> {
                        reverse.drainTo(maxChangeSeq());
                        reverse.stop();
                    }
                    case REPLAY -> replay(cutoverSeq, rollbackSeq);
                    case REPLAY_BY_TOPIC -> replayByTopic(cutoverSeq, rollbackSeq, false);
                    case REPLAY_BY_TOPIC_DEFER -> replayByTopic(cutoverSeq, rollbackSeq, true);
                }
                owner.set(src);
                freezeMs = (System.nanoTime() - t0) / 1e6;
            } finally {
                gate.writeLock().unlock();
            }
            if (reverse != null && reverse.running.get()) {
                // 비우지 않고 돌린 경우: 밀린 것은 되돌린 뒤에 도착한다
                long target = maxChangeSeq();
                reverse.drainTo(target);
                reverse.stop();
            }

            Thread.sleep(AFTER_ROLLBACK_MS);
            writing.set(false);
            for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
            pool.shutdown();

            Map<Long, Item> truth = truth(Long.MAX_VALUE);
            Set<Long> touched = pidsBetween(cutoverSeq, rollbackSeq);
            int[] diff = compare(src, truth);
            int wrongFromBug = 0;
            if (dstBug) {
                Map<Long, Item> srcRows = rows(src);
                for (long pid : bugged) {
                    Item t = truth.get(pid);
                    Item s = srcRows.get(pid);
                    if (s != null && t != null && s.amount != t.amount) wrongFromBug++;
                }
            }
            String recon = dstBug
                    ? String.format("D0[fp=%d,detect_ms=%.0f],D1000[fp=%d,detect_ms=%.0f]",
                    rec0.falsePositives.get(), rec0.detectMs(firstBugAt.get()),
                    rec1.falsePositives.get(), rec1.detectMs(firstBugAt.get()))
                    : "-";
            recon += String.format(";missing_on_arrival=%d,deferred_applied=%d,reverse_gap_waits=%d", missingOnArrival.get(),
                    deferredApplied.get(), reverse == null ? 0 : reverse.gapWaits.get());
            recon += String.format(",reverse_delay_ms=%d,lag_at_rollback=%d", reverseDelayMs, lagAtRollback);
            if (mode == Mode.NONE) {
                // 판정 1: 하네스 확인. 되돌리기를 안 하면 전환 뒤 닿은 항목이 전부 어긋나야 한다.
                // 여기서 멈추면 나머지 방식을 못 보므로 기록만 하고 테스트 끝에서 단언한다
                boolean ok = diff[0] + diff[1] == touched.size();
                System.out.printf("CUTOVER-HARNESS-CHECK expected=%d actual=%d ok=%s%n", touched.size(), diff[0] + diff[1], ok);
                if (!ok) HARNESS_OK.set(false);
            }
            return new Result(mode, touched.size(), diff[0], diff[1], diff[2], wrongFromBug, bugged.size(), freezeMs,
                    maxLag.get(), writesForward, writes.get(), recon);
        }

        // --- 쓰기 ---

        private Void writerLoop(int writer) throws Exception {
            Map<Long, Item> mine = new HashMap<>();
            List<Long> ids = new ArrayList<>();
            // 시드 중 내 몫
            for (long pid = writer; pid < SEED_ITEMS; pid += WRITERS) {
                mine.put(pid, new Item(10_000, "PENDING_CONFIRMATION", -1));
                ids.add(pid);
            }
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            while (writing.get()) {
                int dice = rnd.nextInt(10);
                Op op;
                long pid;
                long value = 0;
                if (dice < 4 || ids.isEmpty()) {
                    op = Op.APPROVE;
                    pid = SEED_ITEMS + nextPid.getAndIncrement() * WRITERS + writer;
                    while (pid % WRITERS != writer) pid++;
                    value = 10_000;
                } else {
                    pid = ids.get(rnd.nextInt(ids.size()));
                    Item it = mine.get(pid);
                    if (dice < 7 && it.status.equals("PENDING_CONFIRMATION")) {
                        op = Op.CONFIRM;
                    } else if (it.amount >= CANCEL_DELTA) {
                        op = Op.CANCEL;
                        value = CANCEL_DELTA;
                    } else {
                        continue;
                    }
                }
                gate.readLock().lock();
                try {
                    appendLog(pid, op, value);
                    applyTo(owner.get(), pid, op, value);
                } finally {
                    gate.readLock().unlock();
                }
                if (op == Op.APPROVE) {
                    mine.put(pid, new Item(value, "PENDING_CONFIRMATION", -1));
                    ids.add(pid);
                } else {
                    mine.get(pid).apply(op, value);
                }
                writes.incrementAndGet();
            }
            return null;
        }

        private void appendLog(long pid, Op op, long value) throws SQLException {
            try (Connection c = log.getConnection();
                 PreparedStatement ps = c.prepareStatement("INSERT INTO events (payment_id, op, value, at) VALUES (?, ?, ?, NOW(6))")) {
                ps.setLong(1, pid);
                ps.setString(2, op.name());
                ps.setLong(3, value);
                ps.executeUpdate();
            }
        }

        private void applyTo(HikariDataSource store, long pid, Op op, long value) throws SQLException {
            boolean toDst = store == dst;
            try (Connection c = store.getConnection()) {
                c.setAutoCommit(false);
                try {
                    applySql(c, pid, op, value, toDst && dstBug && op == Op.CANCEL && isBugged(pid));
                    if (toDst && mode.reverse) {
                        try (PreparedStatement ps = c.prepareStatement("INSERT INTO change_log (payment_id) VALUES (?)")) {
                            ps.setLong(1, pid);
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                } catch (SQLException e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }

        /** dst 에 반영하는 부분 취소 100건에 1건은 금액을 빼지 않는다. */
        private boolean isBugged(long pid) {
            if (dstCancels.incrementAndGet() % 100 != 99) return false;
            bugged.add(pid);
            firstBugAt.compareAndSet(0, System.nanoTime());
            return true;
        }

        // --- 되돌리기 ---

        final AtomicInteger missingOnArrival = new AtomicInteger();
        final AtomicInteger deferredApplied = new AtomicInteger();

        /**
         * 카프카 재생 흉내: 조작마다 토픽이 다르다(payment.confirmed 와 payment.canceled 가 다른 토픽이듯).
         * 토픽마다 컨슈머 하나가 자기 토픽만 순서대로 읽어 옛 저장소에 반영한다. 토픽 사이 순서는 없다.
         * defer 면 대상 행이 아직 없을 때 버리지 않고 모아 두었다가 다시 시도한다(main 의 "없음 = 미도착" 보류와 같은 모양).
         */
        private void replayByTopic(long fromSeqExclusive, long toSeqInclusive, boolean defer) throws Exception {
            ExecutorService consumers = Executors.newFixedThreadPool(Op.values().length);
            java.util.concurrent.ConcurrentLinkedQueue<Object[]> deferred = new java.util.concurrent.ConcurrentLinkedQueue<>();
            List<Future<?>> fs = new ArrayList<>();
            for (Op topic : Op.values()) {
                fs.add(consumers.submit(() -> {
                    try (Connection lc = log.getConnection();
                         PreparedStatement ps = lc.prepareStatement(
                                 "SELECT payment_id, value FROM events WHERE seq > ? AND seq <= ? AND op = ? ORDER BY seq");
                         Connection sc = src.getConnection()) {
                        ps.setLong(1, fromSeqExclusive);
                        ps.setLong(2, toSeqInclusive);
                        ps.setString(3, topic.name());
                        try (ResultSet rs = ps.executeQuery()) {
                            while (rs.next()) {
                                long pid = rs.getLong(1);
                                long value = rs.getLong(2);
                                if (applySql(sc, pid, topic, value, false) == 0 && topic != Op.APPROVE && !exists(sc, pid)) {
                                    missingOnArrival.incrementAndGet();   // 승인보다 먼저 도착했다
                                    if (defer) deferred.add(new Object[]{pid, topic, value});
                                }
                            }
                        }
                    }
                    return null;
                }));
            }
            for (Future<?> f : fs) f.get(120, TimeUnit.SECONDS);
            consumers.shutdown();
            if (!defer) return;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            try (Connection sc = src.getConnection()) {
                while (!deferred.isEmpty() && System.nanoTime() < deadline) {
                    Object[] d = deferred.poll();
                    long pid = (Long) d[0];
                    if (!exists(sc, pid)) {
                        deferred.add(d);
                        continue;
                    }
                    applySql(sc, pid, (Op) d[1], (Long) d[2], false);
                    deferredApplied.incrementAndGet();
                }
            }
        }

        private boolean exists(Connection c, long pid) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM items WHERE payment_id = ?")) {
                ps.setLong(1, pid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }

        private void replay(long fromSeqExclusive, long toSeqInclusive) throws SQLException {
            try (Connection lc = log.getConnection();
                 PreparedStatement ps = lc.prepareStatement("SELECT payment_id, op, value FROM events WHERE seq > ? AND seq <= ? ORDER BY seq")) {
                ps.setLong(1, fromSeqExclusive);
                ps.setLong(2, toSeqInclusive);
                try (ResultSet rs = ps.executeQuery(); Connection sc = src.getConnection()) {
                    sc.setAutoCommit(false);
                    int n = 0;
                    while (rs.next()) {
                        applySql(sc, rs.getLong(1), Op.valueOf(rs.getString(2)), rs.getLong(3), false);
                        if (++n % 500 == 0) sc.commit();
                    }
                    sc.commit();
                    sc.setAutoCommit(true);
                }
            }
        }

        /** 역방향 동기화: dst 의 변경 기록을 따라 현재 행을 src 로 복사한다. */
        final class ReverseSync {
            final boolean guard;
            final AtomicBoolean running = new AtomicBoolean(true);
            final AtomicLong watermark = new AtomicLong();
            final AtomicInteger gapWaits = new AtomicInteger();
            Thread thread;

            ReverseSync(boolean guard) {
                this.guard = guard;
            }

            void start() {
                thread = new Thread(this::loop, "cutover-reverse-sync");
                thread.setDaemon(true);
                thread.start();
            }

            int lag() {
                try {
                    return (int) (maxChangeSeq() - watermark.get());
                } catch (SQLException e) {
                    return -1;
                }
            }

            void drainTo(long seq) throws InterruptedException {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (watermark.get() < seq && System.nanoTime() < deadline) Thread.sleep(1);
            }

            void stop() throws InterruptedException {
                running.set(false);
                thread.join(10_000);
            }

            private void loop() {
                while (running.get()) {
                    try {
                        List<long[]> changes = new ArrayList<>();
                        try (Connection c = dst.getConnection();
                             PreparedStatement ps = c.prepareStatement(
                                     "SELECT seq, payment_id FROM change_log WHERE seq > ? AND at <= NOW(6) - INTERVAL ? MICROSECOND ORDER BY seq LIMIT 500")) {
                            ps.setLong(1, watermark.get());
                            ps.setLong(2, reverseDelayMs * 1000);
                            try (ResultSet rs = ps.executeQuery()) {
                                while (rs.next()) changes.add(new long[]{rs.getLong(1), rs.getLong(2)});
                            }
                        }
                        // 번호는 받은 순서, 보이는 것은 커밋 순서다. 빈 번호에서 멈추고 기다린다.
                        // 건너뛰면 늦게 커밋된 낮은 번호를 영영 놓친다(binlog 기반 CDC 는 커밋 순서라 이 문제가 없다)
                        List<long[]> prefix = new ArrayList<>();
                        long expected = watermark.get() + 1;
                        for (long[] ch : changes) {
                            if (ch[0] != expected) break;
                            prefix.add(ch);
                            expected++;
                        }
                        if (prefix.isEmpty()) {
                            if (!changes.isEmpty()) gapWaits.incrementAndGet();
                            Thread.sleep(changes.isEmpty() ? 20 : 1);
                            continue;
                        }
                        Set<Long> pids = new HashSet<>();
                        for (long[] ch : prefix) pids.add(ch[1]);
                        copyRows(dst, src, pids, guard);
                        watermark.set(prefix.get(prefix.size() - 1)[0]);
                    } catch (Exception e) {
                        System.out.println("CUTOVER reverse error " + e);
                        return;
                    }
                }
            }
        }

        /** 대조: dst 를 로그에서 계산한 값과 비교. 마지막 이벤트가 settleMs 보다 오래된 항목만. */
        final class Reconciler {
            final long settleMs;
            final AtomicInteger falsePositives = new AtomicInteger();
            final AtomicLong firstDetectAt = new AtomicLong();
            final AtomicBoolean running = new AtomicBoolean(true);
            Thread thread;

            Reconciler(long settleMs) {
                this.settleMs = settleMs;
            }

            void start() {
                thread = new Thread(() -> {
                    while (running.get()) {
                        try {
                            Map<Long, Item> dstRows = rows(dst);   // dst 를 먼저 읽고
                            Map<Long, Long> ageMicros = new HashMap<>();
                            Map<Long, Item> t = truthWithLastEvent(ageMicros);   // 그다음 로그를 읽는다(로그가 늘 앞선다)
                            for (Map.Entry<Long, Item> e : t.entrySet()) {
                                Long age = ageMicros.get(e.getKey());
                                if (age == null || age < settleMs * 1000) continue;
                                Item d = dstRows.get(e.getKey());
                                Item x = e.getValue();
                                boolean mismatch = d == null || d.amount != x.amount || !d.status.equals(x.status)
                                        || d.lastCancelSeq != x.lastCancelSeq;
                                if (!mismatch) continue;
                                if (bugged.contains(e.getKey())) firstDetectAt.compareAndSet(0, System.nanoTime());
                                else falsePositives.incrementAndGet();
                            }
                            Thread.sleep(500);
                        } catch (Exception ex) {
                            return;
                        }
                    }
                }, "cutover-recon-" + settleMs);
                thread.setDaemon(true);
                thread.start();
            }

            void stop() throws InterruptedException {
                running.set(false);
                thread.join(10_000);
            }

            double detectMs(long bugAt) {
                long d = firstDetectAt.get();
                return d == 0 || bugAt == 0 ? -1 : (d - bugAt) / 1e6;
            }
        }
    }

    // =====================================================================================================
    // SQL
    // =====================================================================================================

    /** 영향 받은 행 수. 0 이면 조건이 안 맞았거나 행이 없다. */
    private static int applySql(Connection c, long pid, Op op, long value, boolean bug) throws SQLException {
        switch (op) {
            case APPROVE -> {
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO items (payment_id, order_no, amount, status,"
                        + " last_cancel_seq, version, updated_at) VALUES (?, ?, ?, 'PENDING_CONFIRMATION', -1, 1, NOW(6))")) {
                    ps.setLong(1, pid);
                    ps.setString(2, "ORD-" + pid);
                    ps.setLong(3, value);
                    return ps.executeUpdate();
                }
            }
            case CONFIRM -> {
                try (PreparedStatement ps = c.prepareStatement("UPDATE items SET status = 'CONFIRMED', version = version + 1,"
                        + " updated_at = NOW(6) WHERE payment_id = ? AND status = 'PENDING_CONFIRMATION'")) {
                    ps.setLong(1, pid);
                    return ps.executeUpdate();
                }
            }
            case CANCEL -> {
                String set = bug
                        ? "last_cancel_seq = last_cancel_seq + 1"   // 버그: 순번만 올리고 금액은 그대로
                        : "amount = amount - ?, last_cancel_seq = last_cancel_seq + 1";
                try (PreparedStatement ps = c.prepareStatement("UPDATE items SET " + set + ", version = version + 1,"
                        + " updated_at = NOW(6) WHERE payment_id = ? AND amount >= ?")) {
                    int i = 1;
                    if (!bug) ps.setLong(i++, value);
                    ps.setLong(i++, pid);
                    ps.setLong(i, value);
                    return ps.executeUpdate();
                }
            }
        }
        throw new IllegalStateException(op.name());
    }

    private static void resetSchemas() throws SQLException {
        for (HikariDataSource d : new HikariDataSource[]{src, dst}) {
            try (Connection c = d.getConnection(); Statement s = c.createStatement()) {
                s.execute("DROP TABLE IF EXISTS items");
                s.execute("DROP TABLE IF EXISTS change_log");
                s.execute("CREATE TABLE items (payment_id BIGINT PRIMARY KEY, order_no VARCHAR(64) NOT NULL, amount BIGINT NOT NULL,"
                        + " status VARCHAR(24) NOT NULL, last_cancel_seq INT NOT NULL, version BIGINT NOT NULL,"
                        + " updated_at TIMESTAMP(6) NOT NULL, KEY idx_updated (updated_at)) ENGINE=InnoDB");
                s.execute("CREATE TABLE change_log (seq BIGINT AUTO_INCREMENT PRIMARY KEY, payment_id BIGINT NOT NULL,"
                        + " at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)) ENGINE=InnoDB");
            }
        }
        try (Connection c = log.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS events");
            s.execute("CREATE TABLE events (seq BIGINT AUTO_INCREMENT PRIMARY KEY, payment_id BIGINT NOT NULL, op VARCHAR(10) NOT NULL,"
                    + " value BIGINT NOT NULL, at TIMESTAMP(6) NOT NULL) ENGINE=InnoDB");
        }
    }

    private static void seed() throws SQLException {
        try (Connection sc = src.getConnection(); Connection lc = log.getConnection()) {
            for (int base = 0; base < SEED_ITEMS; base += 1_000) {
                StringBuilder items = new StringBuilder("INSERT INTO items VALUES ");
                StringBuilder events = new StringBuilder("INSERT INTO events (payment_id, op, value, at) VALUES ");
                for (int i = 0; i < 1_000; i++) {
                    long pid = base + i;
                    items.append(i == 0 ? "" : ",").append("(").append(pid).append(",'ORD-").append(pid)
                            .append("',10000,'PENDING_CONFIRMATION',-1,1,NOW(6) - INTERVAL 1 DAY)");
                    events.append(i == 0 ? "" : ",").append("(").append(pid).append(",'APPROVE',10000,NOW(6) - INTERVAL 1 DAY)");
                }
                try (Statement s = sc.createStatement()) {
                    s.executeUpdate(items.toString());
                }
                try (Statement s = lc.createStatement()) {
                    s.executeUpdate(events.toString());
                }
            }
        }
    }

    private static void copyAll(HikariDataSource from, HikariDataSource to) throws SQLException {
        copyRows(from, to, null, false);
    }

    /** 전환 뒤 바뀐 행(updated_at 이 하루 전 시드보다 늦은 행)을 복사. */
    private static void copyChangedSince(HikariDataSource from, HikariDataSource to) throws SQLException {
        Set<Long> pids = new HashSet<>();
        try (Connection c = from.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT payment_id FROM items WHERE updated_at > NOW(6) - INTERVAL 1 HOUR")) {
            while (rs.next()) pids.add(rs.getLong(1));
        }
        copyRows(from, to, pids, false);
    }

    /** pids 가 null 이면 전부. guard 면 버전이 더 클 때만 덮어쓴다. */
    private static void copyRows(HikariDataSource from, HikariDataSource to, Set<Long> pids, boolean guard) throws SQLException {
        List<Object[]> rows = new ArrayList<>();
        try (Connection c = from.getConnection()) {
            if (pids == null) {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT payment_id, order_no, amount, status, last_cancel_seq, version, updated_at FROM items")) {
                    while (rs.next()) rows.add(row(rs));
                }
            } else if (!pids.isEmpty()) {
                List<Long> list = new ArrayList<>(pids);
                for (int i = 0; i < list.size(); i += 500) {
                    List<Long> part = list.subList(i, Math.min(list.size(), i + 500));
                    String in = String.join(",", part.stream().map(String::valueOf).toList());
                    try (Statement s = c.createStatement();
                         ResultSet rs = s.executeQuery("SELECT payment_id, order_no, amount, status, last_cancel_seq, version, updated_at"
                                 + " FROM items WHERE payment_id IN (" + in + ")")) {
                        while (rs.next()) rows.add(row(rs));
                    }
                }
            }
        }
        if (rows.isEmpty()) return;
        String upsert = guard
                ? "INSERT INTO items VALUES (?, ?, ?, ?, ?, ?, ?) AS n ON DUPLICATE KEY UPDATE"
                + " amount = IF(n.version > items.version, n.amount, items.amount),"
                + " status = IF(n.version > items.version, n.status, items.status),"
                + " last_cancel_seq = IF(n.version > items.version, n.last_cancel_seq, items.last_cancel_seq),"
                + " updated_at = IF(n.version > items.version, n.updated_at, items.updated_at),"
                + " version = IF(n.version > items.version, n.version, items.version)"
                : "INSERT INTO items VALUES (?, ?, ?, ?, ?, ?, ?) AS n ON DUPLICATE KEY UPDATE"
                + " amount = n.amount, status = n.status, last_cancel_seq = n.last_cancel_seq, updated_at = n.updated_at,"
                + " version = n.version";
        try (Connection c = to.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                int n = 0;
                for (Object[] r : rows) {
                    for (int i = 0; i < 7; i++) ps.setObject(i + 1, r[i]);
                    ps.addBatch();
                    if (++n % 500 == 0) ps.executeBatch();
                }
                ps.executeBatch();
                c.commit();
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static Object[] row(ResultSet rs) throws SQLException {
        return new Object[]{rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4), rs.getInt(5), rs.getLong(6),
                rs.getTimestamp(7)};
    }

    private static long maxLogSeq() throws SQLException {
        try (Connection c = log.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM events")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static long maxChangeSeq() throws SQLException {
        try (Connection c = dst.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COALESCE(MAX(seq), 0) FROM change_log")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Map<Long, Item> truth(long upToSeq) throws SQLException {
        return truthWithLastEvent(null);
    }

    /**
     * 로그 전체를 순서대로 다시 적용한 상태. ageMicros 가 있으면 결제별 마지막 이벤트의 나이(마이크로초)도 채운다.
     * 나이는 DB 시계 하나로 계산한다(JVM 시계와 VM 안 MySQL 시계가 어긋나면 그만큼 오탐이 섞인다).
     */
    private static Map<Long, Item> truthWithLastEvent(Map<Long, Long> ageMicros) throws SQLException {
        Map<Long, Item> t = new HashMap<>();
        try (Connection c = log.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT payment_id, op, value, TIMESTAMPDIFF(MICROSECOND, at, NOW(6)) FROM events ORDER BY seq")) {
            while (rs.next()) {
                long pid = rs.getLong(1);
                Op op = Op.valueOf(rs.getString(2));
                long value = rs.getLong(3);
                Item it = t.get(pid);
                if (op == Op.APPROVE) {
                    t.put(pid, new Item(value, "PENDING_CONFIRMATION", -1));
                } else if (it != null) {
                    it.apply(op, value);
                }
                if (ageMicros != null) ageMicros.put(pid, rs.getLong(4));
            }
        }
        return t;
    }

    private static Set<Long> pidsBetween(long fromExclusive, long toInclusive) throws SQLException {
        Set<Long> s = new HashSet<>();
        try (Connection c = log.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT DISTINCT payment_id FROM events WHERE seq > ? AND seq <= ?")) {
            ps.setLong(1, fromExclusive);
            ps.setLong(2, toInclusive);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) s.add(rs.getLong(1));
            }
        }
        return s;
    }

    private static Map<Long, Item> rows(HikariDataSource d) throws SQLException {
        Map<Long, Item> m = new HashMap<>();
        try (Connection c = d.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT payment_id, amount, status, last_cancel_seq FROM items")) {
            while (rs.next()) m.put(rs.getLong(1), new Item(rs.getLong(2), rs.getString(3), rs.getInt(4)));
        }
        return m;
    }

    /** {누락, 값 다름, 여분} */
    private static int[] compare(HikariDataSource d, Map<Long, Item> truth) throws SQLException {
        Map<Long, Item> actual = rows(d);
        int missing = 0;
        int stale = 0;
        for (Map.Entry<Long, Item> e : truth.entrySet()) {
            Item a = actual.get(e.getKey());
            Item t = e.getValue();
            if (a == null) missing++;
            else if (a.amount != t.amount || !a.status.equals(t.status) || a.lastCancelSeq != t.lastCancelSeq) stale++;
        }
        int extra = 0;
        for (Long pid : actual.keySet()) if (!truth.containsKey(pid)) extra++;
        return new int[]{missing, stale, extra};
    }
}
