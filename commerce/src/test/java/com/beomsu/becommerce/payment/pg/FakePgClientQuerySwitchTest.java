package com.beomsu.becommerce.payment.pg;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 복구 실험(#330)용 가짜 PG 스위치: 진행 중인 건을 차례로 풀어 주고, 조회를 정해진 시간 동안 실패시키고, PG 에 닿은 조회를 센다.
 * 실험이 이 스위치를 믿고 지연과 호출 수를 읽으므로 동작을 고정해 둔다.
 */
class FakePgClientQuerySwitchTest {

    @Test
    @DisplayName("진행 중인 건은 첫 응답에서 (키 끝 번호 + 1) × 간격이 지나면 승인으로 바뀐다")
    void inProgressKeysAreReleasedInOrder() {
        FakePgClient pg = new FakePgClient();
        ReflectionTestUtils.setField(pg, "queryInProgressPrefix", "rq-stuck-");
        ReflectionTestUtils.setField(pg, "queryInProgressReleaseStepMs", 60_000L);

        assertThat(pg.query("rq-stuck-0").status()).isEqualTo(PgPaymentStatus.IN_PROGRESS);
        // 첫 응답 시각을 3분 앞으로 옮기면 0·1 번은 풀리고(1분·2분) 2 번은 딱 3분이라 풀리며 3 번은 아직이다
        ((java.util.concurrent.atomic.AtomicLong) ReflectionTestUtils.getField(pg, "firstInProgressAt"))
                .addAndGet(-180_000L);
        assertThat(pg.query("rq-stuck-0").status()).isEqualTo(PgPaymentStatus.APPROVED);
        assertThat(pg.query("rq-stuck-2").status()).isEqualTo(PgPaymentStatus.APPROVED);
        assertThat(pg.query("rq-stuck-3").status()).isEqualTo(PgPaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("풀어 주는 간격이 0 이면 진행 중인 건은 계속 진행 중이다(#248 동작 그대로)")
    void withoutReleaseStepKeysStayInProgress() {
        FakePgClient pg = new FakePgClient();
        ReflectionTestUtils.setField(pg, "queryInProgressPrefix", "rq-stuck-");

        assertThat(pg.query("rq-stuck-0").status()).isEqualTo(PgPaymentStatus.IN_PROGRESS);
        assertThat(pg.query("rq-stuck-99").status()).isEqualTo(PgPaymentStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("실패 접두어의 조회는 첫 실패에서 정한 시간 동안 예외이고 그 뒤 정상으로 답한다. 닿은 조회는 모두 센다")
    void failPrefixFailsForDurationAndCountsCalls() {
        FakePgClient pg = new FakePgClient();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        pg.registerMetrics(registry);
        ReflectionTestUtils.setField(pg, "queryFailPrefix", "rq-fail-");
        ReflectionTestUtils.setField(pg, "queryFailForMs", 300_000L);

        assertThatThrownBy(() -> pg.query("rq-fail-1")).isInstanceOf(IllegalStateException.class);
        assertThat(pg.query("other-key").status()).isEqualTo(PgPaymentStatus.NOT_FOUND);
        ((java.util.concurrent.atomic.AtomicLong) ReflectionTestUtils.getField(pg, "firstFailAt"))
                .addAndGet(-300_000L);
        assertThat(pg.query("rq-fail-1").status()).isEqualTo(PgPaymentStatus.NOT_FOUND);

        assertThat(registry.get("fake.pg.query.calls").functionCounter().count()).isEqualTo(3.0);
    }
}
