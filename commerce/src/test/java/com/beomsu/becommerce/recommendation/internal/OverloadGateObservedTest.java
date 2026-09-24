package com.beomsu.becommerce.recommendation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 관측 처리량으로 대기를 추정한다(#264). 설정은 50ms·4동시(80/s)라고 믿는데 실제로는 40/s 만 끝나면, 같은 줄 길이의 대기를
 * 두 배로 봐야 예산이 지켜진다.
 */
class OverloadGateObservedTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private OverloadGate gate(OverloadGate.AdmissionEstimate estimate) {
        return new OverloadGate(OverloadPolicy.ADMISSION, 24, 100, 4, 50, 12, GenerationScope.RANKING, 4, 15, estimate, now::get);
    }

    @Test
    @DisplayName("표본이 모자라면 설정값으로, 차면 관측 처리량으로 대기를 본다")
    void usesObservedThroughputOnceEnoughSamples() {
        OverloadGate g = gate(OverloadGate.AdmissionEstimate.OBSERVED);
        assertThat(g.estimatedWaitMs(7)).isEqualTo(50.0);             // 줄 4 × 50ms / 4 — 설정값

        for (int i = 0; i < 80; i++) {                                 // 2초에 80건 = 40/s
            now.addAndGet(25);
            g.completed();
        }
        assertThat(g.estimatedWaitMs(7)).isCloseTo(100.0, org.assertj.core.data.Percentage.withPercentage(5));   // 줄 4 ÷ 0.04/ms(칸 경계에서 몇 건 빠진다)
    }

    @Test
    @DisplayName("오래된 완료는 창에서 빠진다")
    void oldCompletionsExpire() {
        OverloadGate g = gate(OverloadGate.AdmissionEstimate.OBSERVED);
        for (int i = 0; i < 80; i++) {
            now.addAndGet(25);
            g.completed();
        }
        now.addAndGet(5_000);                                          // 5초 동안 아무것도 안 끝났다
        assertThat(g.observedThroughputPerMs()).isEqualTo(-1.0);
    }

    @Test
    @DisplayName("CONFIGURED 는 관측을 무시한다")
    void configuredIgnoresObservations() {
        OverloadGate g = gate(OverloadGate.AdmissionEstimate.CONFIGURED);
        for (int i = 0; i < 80; i++) {
            now.addAndGet(25);
            g.completed();
        }
        assertThat(g.estimatedWaitMs(7)).isEqualTo(50.0);
    }
}
