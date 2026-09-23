package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.FactPack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 운영자용 서술의 <b>지연 예산</b>을 실측한다. 읽기 타임아웃이 60초로 잡혀 있는데,
 * 그 값이 <b>운영자 화면이 1분 멈춰도 된다</b>는 뜻이라는 것을 아무도 정하지 않았다.
 *
 * <p>순차 지연과 동시 요청을 함께 잰다. 운영자는 한 명이 아니고, 로컬 모델은 요청을 큐에
 * 세우므로 <b>혼자 쟀을 때의 값이 여럿일 때 그대로 유지되지 않는다.</b>
 *
 * <p>이상거래 지연 예산(17 문서)과 같은 자리다. 거기서는 동기 차단을 넣을지 정하려고 쟀고,
 * 여기서는 타임아웃과 화면 동작을 정하려고 잰다.
 */
@Tag("eval")
@DisplayName("서술 지연 예산 — 순차와 동시")
class NarrativeLatencyBudgetTest {

    private static final int SEQUENTIAL = Integer.getInteger("eval.latencyCases", 10);
    private static final int CONCURRENT = Integer.getInteger("eval.concurrency", 4);

    private FactPack caseOf(int i) {
        return NarrativeBlindDumpTest.caseOf(i);
    }

    private static String percentiles(List<Long> ms) {
        List<Long> s = new ArrayList<>(ms);
        s.sort(null);
        long p50 = s.get(s.size() / 2);
        long p95 = s.get(Math.min(s.size() - 1, (int) Math.ceil(s.size() * 0.95) - 1));
        return "n=%d  p50=%,dms  p95=%,dms  max=%,dms".formatted(
                s.size(), p50, p95, s.get(s.size() - 1));
    }

    @Test
    @DisplayName("순차 호출과 동시 호출의 지연을 나란히 잰다")
    void measure() throws Exception {
        var model = new OllamaNarrativeAdapter("http://localhost:11434", "qwen3:8b", 120);
        var template = new TemplateNarrativeAdapter();

        List<Long> tmpl = new ArrayList<>();
        for (int i = 0; i < SEQUENTIAL; i++) {
            long t0 = System.nanoTime();
            template.narrate(caseOf(i));
            tmpl.add(Duration.ofNanos(System.nanoTime() - t0).toMillis());
        }

        List<Long> seq = new ArrayList<>();
        for (int i = 0; i < SEQUENTIAL; i++) {
            long t0 = System.nanoTime();
            model.narrate(caseOf(i));
            seq.add(Duration.ofNanos(System.nanoTime() - t0).toMillis());
        }

        List<Long> con = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(CONCURRENT)) {
            List<Future<Long>> futures = new ArrayList<>();
            Instant start = Instant.now();
            for (int i = 0; i < SEQUENTIAL; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    long t0 = System.nanoTime();
                    model.narrate(caseOf(idx));
                    return Duration.ofNanos(System.nanoTime() - t0).toMillis();
                }));
            }
            for (Future<Long> f : futures) {
                con.add(f.get(5, TimeUnit.MINUTES));
            }
            System.out.printf("%n  동시 %d개로 %d건 전체 소요: %,dms%n",
                    CONCURRENT, SEQUENTIAL, Duration.between(start, Instant.now()).toMillis());
        }

        System.out.println("\n╔══ 서술 지연 예산 ══");
        System.out.println("  템플릿        " + percentiles(tmpl));
        System.out.println("  모델(순차)     " + percentiles(seq));
        System.out.printf("  모델(동시 %d)   %s%n", CONCURRENT, percentiles(con));
        System.out.println("╚═══════════════════");
    }
}
