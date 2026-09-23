package com.beomsu.becommerce.fraud.velocity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVelocityCounterTest {

    @Test
    @DisplayName("같은 윈도우 내 반복 시도는 누적된다")
    void countsWithinWindow() {
        InMemoryVelocityCounter counter = new InMemoryVelocityCounter();
        AtomicLong clock = new AtomicLong(1_000_000);
        counter.setClock(clock::get);

        assertThat(counter.recordAndCount("k")).isEqualTo(1);
        assertThat(counter.recordAndCount("k")).isEqualTo(2);
        assertThat(counter.recordAndCount("k")).isEqualTo(3);
    }

    @Test
    @DisplayName("윈도우(60초)를 벗어난 오래된 시도는 만료된다")
    void oldHitsExpire() {
        InMemoryVelocityCounter counter = new InMemoryVelocityCounter();
        AtomicLong clock = new AtomicLong(1_000_000);
        counter.setClock(clock::get);

        counter.recordAndCount("k");                 // t=1,000,000
        counter.recordAndCount("k");                 // 누적 2
        clock.addAndGet(61_000);                      // 61초 경과 → 이전 2건 만료
        assertThat(counter.recordAndCount("k")).isEqualTo(1); // 새 1건만

        assertThat(counter.recordAndCount("other")).isEqualTo(1); // 키별 독립
    }
}
