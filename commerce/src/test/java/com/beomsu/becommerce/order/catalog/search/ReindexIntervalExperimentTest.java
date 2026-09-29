package com.beomsu.becommerce.order.catalog.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 전체 재색인 주기(27절⑨, Instacart "Item Availability Architecture") — 이벤트 유실 대비 신선도
 * 대 재색인 비용.
 *
 * <p>{@link RefreshingLuceneSearch}(ADR-056)는 이벤트 갱신에 전체 재색인 주기를 덧댄다. 지금 주기는
 * "통째로 만드는 편이 단순하다"는 근거뿐이라 숫자로 정한 적이 없다. 이벤트가 일부 유실되면(컨슈머
 * 크래시 등) 그 상품 색인은 유실된 채로 남고 다음 전체 재색인이 와야 정답으로 돌아온다 — 이 실험은
 * 그 "정정까지 걸리는 시간(staleness)"과 재색인 비용(수행 횟수)의 트레이드오프를 잰다.
 *
 * <p><b>가상 시계</b>다. 실제 {@code Thread.sleep}·Lucene 색인 없이 이벤트·재색인 틱의 시간표를 산술로만
 * 계산해, 이 기계의 다른 부하와 무관하게 결정적이고 즉시 끝난다. {@code RefreshingLuceneSearch}에는
 * 배선하지 않는다.
 *
 * <p>{@code ./gradlew experimentTest --tests '*ReindexInterval*'}로 실행한다.
 */
@Tag("experiment")
class ReindexIntervalExperimentTest {

    private static final double EVENT_RATE_PER_SEC = 10.0;
    private static final double OBSERVE_SECONDS = 7200.0; // 2시간(가상 시계, 실제로는 산술만)
    private static final double[] LOSS_RATES = {0.0, 0.01, 0.05};
    /** 0은 "끔"을 뜻한다 — 재색인 틱이 없어 유실된 이벤트가 영영 정정되지 않는다. */
    private static final double[] REINDEX_INTERVALS_SEC = {60, 300, 1800, 0};

    private record Result(double lossRate, double intervalSec, int lostEvents, int recoveredEvents,
                           int unrecoveredEvents, double p99StalenessSec, long reindexCount) {
    }

    @Test
    @DisplayName("유실률·재색인 주기별 staleness p99·재색인 횟수·회복 안 된 유실 문서 수를 비교한다")
    void compareReindexIntervals() {
        int totalEvents = (int) Math.round(EVENT_RATE_PER_SEC * OBSERVE_SECONDS);
        List<Result> results = new ArrayList<>();
        for (double lossRate : LOSS_RATES) {
            // 같은 유실 패턴을 재색인 주기마다 재사용한다 — 트래픽 자체가 아니라 재색인 정책만 바꾼다.
            Random random = new Random(Double.hashCode(lossRate) ^ 0x5EED);
            boolean[] lost = new boolean[totalEvents];
            for (int i = 0; i < totalEvents; i++) {
                lost[i] = random.nextDouble() < lossRate;
            }
            for (double interval : REINDEX_INTERVALS_SEC) {
                results.add(run(lossRate, interval, lost, totalEvents));
            }
        }

        for (Result r : results) {
            System.out.printf(
                    "REINDEX-INTERVAL loss=%.2f interval_s=%.0f lost=%d recovered=%d unrecovered=%d "
                            + "p99_staleness_s=%.1f reindex_count=%d%n",
                    r.lossRate, r.intervalSec, r.lostEvents, r.recoveredEvents, r.unrecoveredEvents,
                    r.p99StalenessSec, r.reindexCount);
        }

        Map<String, Result> byKey = new HashMap<>();
        for (Result r : results) {
            byKey.put(key(r.lossRate, r.intervalSec), r);
        }

        // 판정 기준(이슈 #426, 측정 전에 적음) — 조건이 실제로 섰다는 증거부터 확인한다.

        // 1. 유실 0%에서는 staleness p99가 0이다(유실 이벤트 자체가 없다는 증거부터 확인).
        for (double interval : REINDEX_INTERVALS_SEC) {
            Result r = byKey.get(key(0.0, interval));
            assertThat(r.lostEvents).isEqualTo(0);
            assertThat(r.p99StalenessSec).isEqualTo(0.0);
        }

        // 2. 같은 재색인 주기(300초)에서 유실률이 오를수록 staleness p99가 커진다.
        Result loss0 = byKey.get(key(0.0, 300));
        Result loss1 = byKey.get(key(0.01, 300));
        Result loss5 = byKey.get(key(0.05, 300));
        assertThat(loss1.lostEvents).isGreaterThan(0); // 유실이 실제로 있었다는 증거
        assertThat(loss5.lostEvents).isGreaterThan(loss1.lostEvents);
        assertThat(loss1.p99StalenessSec).isGreaterThanOrEqualTo(loss0.p99StalenessSec);
        assertThat(loss5.p99StalenessSec).isGreaterThanOrEqualTo(loss1.p99StalenessSec);

        // 3. 같은 유실률(5%)에서 주기가 짧을수록 staleness p99가 뚜렷이 작아진다.
        Result i60 = byKey.get(key(0.05, 60));
        Result i300 = byKey.get(key(0.05, 300));
        Result i1800 = byKey.get(key(0.05, 1800));
        assertThat(i60.p99StalenessSec).isLessThan(i300.p99StalenessSec);
        assertThat(i300.p99StalenessSec).isLessThan(i1800.p99StalenessSec);

        // 4. 주기가 짧을수록 관측 시간 동안 재색인 수행 횟수가 뚜렷이 늘어난다.
        assertThat(i60.reindexCount).isGreaterThan(i300.reindexCount);
        assertThat(i300.reindexCount).isGreaterThan(i1800.reindexCount);

        // 5. 재색인을 끄면(0) 유실률 1%·5%에서 관측 종료까지 회복 안 된 유실 문서 수가 0보다 크다.
        Result off1 = byKey.get(key(0.01, 0));
        Result off5 = byKey.get(key(0.05, 0));
        assertThat(off1.unrecoveredEvents).isGreaterThan(0);
        assertThat(off5.unrecoveredEvents).isGreaterThan(0);
        assertThat(off5.reindexCount).isEqualTo(0L);
    }

    private static String key(double lossRate, double intervalSec) {
        return lossRate + "@" + intervalSec;
    }

    private Result run(double lossRate, double intervalSec, boolean[] lost, int totalEvents) {
        List<Double> stalenessDurations = new ArrayList<>();
        int lostEvents = 0;
        int unrecovered = 0;
        for (int i = 0; i < totalEvents; i++) {
            if (!lost[i]) {
                continue;
            }
            lostEvents++;
            double eventTime = i / EVENT_RATE_PER_SEC;
            double nextTick = nextReindexTick(eventTime, intervalSec);
            if (nextTick < 0 || nextTick > OBSERVE_SECONDS) {
                unrecovered++;
                continue;
            }
            stalenessDurations.add(nextTick - eventTime);
        }
        Collections.sort(stalenessDurations);
        double p99 = percentile(stalenessDurations, 0.99);
        long reindexCount = intervalSec > 0 ? (long) Math.floor(OBSERVE_SECONDS / intervalSec) : 0;
        return new Result(lossRate, intervalSec, lostEvents, stalenessDurations.size(), unrecovered, p99,
                reindexCount);
    }

    /** 재색인 주기가 0(끔)이면 -1(영영 안 옴). 아니면 이벤트 시각 이후 가장 가까운 재색인 틱. */
    private static double nextReindexTick(double eventTime, double intervalSec) {
        if (intervalSec <= 0) {
            return -1;
        }
        return Math.ceil(eventTime / intervalSec) * intervalSec;
    }

    private static double percentile(List<Double> sorted, double p) {
        if (sorted.isEmpty()) {
            return 0.0;
        }
        int idx = Math.min(sorted.size() - 1, (int) Math.ceil(p * sorted.size()) - 1);
        return sorted.get(Math.max(0, idx));
    }
}
