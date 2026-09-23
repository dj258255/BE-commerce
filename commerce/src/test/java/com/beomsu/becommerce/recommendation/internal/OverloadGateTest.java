package com.beomsu.becommerce.recommendation.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 과부하 문의 정책별 판단을 고정한다 — E3의 독립변수가 실제로 다르게 동작하는가.
 *
 * <p>모델 용량은 4동시/50ms로 둔다(처리량 80/s). 그러면 대기 예상은
 * {@code (진행 중 − 4 + 1) × 50 / 4}ms다.
 */
class OverloadGateTest {

    private static final int CONCURRENCY = 4;
    private static final long LATENCY_MS = 50;
    private static final int MAX_IN_FLIGHT = 8;
    private static final long BUDGET_MS = 100;
    private static final int RESULT_SIZE = 12;
    private static final int AR_PREFIX = 4;
    private static final long PER_ITEM_MS = 15;

    private OverloadGate gate(OverloadPolicy policy) {
        return new OverloadGate(policy, MAX_IN_FLIGHT, BUDGET_MS, CONCURRENCY, LATENCY_MS,
                RESULT_SIZE, GenerationScope.RANKING, AR_PREFIX, PER_ITEM_MS);
    }

    @Test
    @DisplayName("UNBOUNDED는 아무도 거절하지 않는다 — 줄은 모델 쪽에서 무한히 자란다")
    void unboundedNeverRejects() {
        OverloadGate gate = gate(OverloadPolicy.UNBOUNDED);

        for (int i = 0; i < 10_000; i++) {
            assertThat(gate.admit()).isTrue();
        }
    }

    @Test
    @DisplayName("BOUNDED는 상한에서 자른다 — 그리고 기다리지 않는다")
    void boundedRejectsAtLimit() {
        OverloadGate gate = gate(OverloadPolicy.BOUNDED);

        for (int i = 0; i < MAX_IN_FLIGHT; i++) {
            assertThat(gate.admit()).as("상한까지는 받는다 (%d)", i).isTrue();
        }
        assertThat(gate.admit()).as("상한을 넘으면 거절").isFalse();

        gate.release();
        assertThat(gate.admit()).as("하나 빠지면 다시 받는다").isTrue();
    }

    @Test
    @DisplayName("ADMISSION은 대기 예상으로 자른다 — 모델이 감당할 수 있는 만큼만 줄을 세운다")
    void admissionRejectsWhenEstimatedWaitExceedsBudget() {
        // 예산 100ms, 처리량 80/s(12.5ms당 한 건) → 대기 예상 100ms가 되는 지점에서 자른다.
        OverloadGate gate = gate(OverloadPolicy.ADMISSION);

        int admitted = 0;
        while (gate.admit()) {
            admitted++;
            assertThat(admitted).isLessThan(1000);
        }

        // 마지막으로 **받은** 것의 예상은 예산 이내였고, **거절된** 것의 예상은 넘었다.
        assertThat(admitted).isGreaterThan(CONCURRENCY);
        assertThat(gate.estimatedWaitMs(admitted - 1)).isLessThanOrEqualTo(BUDGET_MS);
        assertThat(gate.estimatedWaitMs(admitted)).isGreaterThan(BUDGET_MS);
    }

    @Test
    @DisplayName("대기 예상은 모델 동시성을 넘는 몫만 센다 — 줄은 용량을 넘은 뒤부터 선다")
    void waitEstimateIgnoresCapacity() {
        OverloadGate gate = gate(OverloadPolicy.ADMISSION);

        // 진행 중 3개면 아직 놀고 있는 슬롯이 있다 → 내가 서도 앞에 아무도 없다.
        assertThat(gate.estimatedWaitMs(CONCURRENCY - 1)).isZero();
        // 용량(4)이 다 찬 상태에서 내가 5번째 → 앞에 1건 = 12.5ms
        assertThat(gate.estimatedWaitMs(CONCURRENCY)).isEqualTo(12.5);
        assertThat(gate.estimatedWaitMs(CONCURRENCY + 1)).isEqualTo(25.0);
        assertThat(gate.estimatedWaitMs(CONCURRENCY + 9)).isEqualTo(125.0);
    }

    @Test
    @DisplayName("모델 용량이 다르면 같은 진행 중 수라도 대기 예상이 다르다 — 상한을 손으로 고르지 않아도 되는 이유")
    void waitEstimateFollowsModelCapacity() {
        OverloadGate slow = new OverloadGate(OverloadPolicy.ADMISSION, MAX_IN_FLIGHT, BUDGET_MS, 2, 100,
                RESULT_SIZE, GenerationScope.RANKING, AR_PREFIX, PER_ITEM_MS);
        OverloadGate fast = new OverloadGate(OverloadPolicy.ADMISSION, MAX_IN_FLIGHT, BUDGET_MS, 8, 25,
                RESULT_SIZE, GenerationScope.RANKING, AR_PREFIX, PER_ITEM_MS);

        int inFlight = 12;
        assertThat(slow.estimatedWaitMs(inFlight)).isGreaterThan(BUDGET_MS);
        assertThat(fast.estimatedWaitMs(inFlight)).isLessThanOrEqualTo(BUDGET_MS);
    }

    @Test
    @DisplayName("생성 범위가 지연을 바꾸면 대기 예상도 바뀐다 — 정책이 모델 지연을 함께 봐야 하는 이유(E5)")
    void waitEstimateFollowsGenerationScope() {
        // 같은 조건에서 범위만 바꾼다. 전체 AR 은 12항목을 직렬 생성해 지연이 훨씬 길다.
        OverloadGate ranking = new OverloadGate(OverloadPolicy.ADMISSION, MAX_IN_FLIGHT, BUDGET_MS,
                CONCURRENCY, LATENCY_MS, RESULT_SIZE, GenerationScope.RANKING, AR_PREFIX, PER_ITEM_MS);
        OverloadGate fullAr = new OverloadGate(OverloadPolicy.ADMISSION, MAX_IN_FLIGHT, BUDGET_MS,
                CONCURRENCY, LATENCY_MS, RESULT_SIZE, GenerationScope.FULL_AR, AR_PREFIX, PER_ITEM_MS);

        // 랭킹: 50ms → 5번째부터 12.5ms. 전체 AR: 50 + 12×15 = 230ms → 같은 자리에서 훨씬 길다.
        assertThat(ranking.estimatedWaitMs(CONCURRENCY)).isEqualTo(12.5);
        assertThat(fullAr.estimatedWaitMs(CONCURRENCY)).isEqualTo(57.5);
        // 그 결과 같은 진행 중 수에서 전체 AR 만 예산을 넘긴다 — 범위를 바꾸면 정책도 같이 봐야 한다.
        assertThat(ranking.estimatedWaitMs(CONCURRENCY + 3)).isLessThanOrEqualTo(BUDGET_MS);
        assertThat(fullAr.estimatedWaitMs(CONCURRENCY + 3)).isGreaterThan(BUDGET_MS);
    }
}
