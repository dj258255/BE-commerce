package com.beomsu.becommerce.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** 고정 배정(#256): 같은 입력이면 같은 변형, 비율은 설정대로, 꺼져 있으면 변형이 없다. 솔트를 바꾸면 다시 섞인다. */
class ExperimentAssignerTest {

    private ExperimentAssigner assigner(String salt, int percent, boolean enabled) {
        MockEnvironment env = new MockEnvironment()
                .withProperty("app.experiments.rec-history.enabled", String.valueOf(enabled))
                .withProperty("app.experiments.rec-history.salt", salt)
                .withProperty("app.experiments.rec-history.treatment-percent", String.valueOf(percent));
        return new ExperimentAssigner(env, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("같은 사용자는 몇 번을 물어도 같은 변형이다")
    void sticky() {
        ExperimentAssigner a = assigner("s1", 50, true);
        for (long user = 1; user <= 1_000; user++) {
            String first = a.assign("rec-history", user).variant();
            assertThat(a.assign("rec-history", user).variant()).isEqualTo(first);
            assertThat(assigner("s1", 50, true).assign("rec-history", user).variant()).isEqualTo(first);
        }
    }

    @Test
    @DisplayName("실험군 비율이 설정에 가깝고(10만 명 ±1%p), 솔트가 다르면 다른 사람이 실험군이 된다")
    void ratioAndSaltReshuffles() {
        ExperimentAssigner a = assigner("s1", 30, true);
        ExperimentAssigner b = assigner("s2", 30, true);
        long treated = LongStream.rangeClosed(1, 100_000).filter(u -> a.assign("rec-history", u).treatment()).count();
        assertThat(treated / 100_000.0).isBetween(0.29, 0.31);
        long agree = LongStream.rangeClosed(1, 10_000)
                .filter(u -> a.assign("rec-history", u).treatment() == b.assign("rec-history", u).treatment()).count();
        assertThat(agree / 10_000.0).isLessThan(0.65);   // 독립이면 0.3²+0.7² = 0.58
    }

    @Test
    @DisplayName("꺼져 있으면 변형이 없다(노출에 실험을 적지 않는다)")
    void disabled() {
        ExperimentAssigner.Assignment x = assigner("s1", 50, false).assign("rec-history", 7);
        assertThat(x.active()).isFalse();
        assertThat(x.variant()).isNull();
    }
}
