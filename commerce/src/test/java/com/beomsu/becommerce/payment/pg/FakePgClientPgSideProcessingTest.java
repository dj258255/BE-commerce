package com.beomsu.becommerce.payment.pg;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PG 읽기 타임아웃 5초 대 60초 비교용 가짜 PG 스위치. 우리가 끊어도 PG 는 계속 처리하고, 그동안 같은 멱등키는
 * 409 로 결과 모름이 되며, PG 쪽 동시 처리 한도는 끊긴 호출까지 센다. 실험이 이 동작을 믿고 결과를 읽으므로 고정해 둔다.
 */
class FakePgClientPgSideProcessingTest {

    private static FakePgClient pg(long latencyMs, long readTimeoutMs, int contract) {
        FakePgClient pg = new FakePgClient();
        pg.setApproveLatencyMillis(latencyMs);
        pg.setReadTimeoutMillis(readTimeoutMs);
        ReflectionTestUtils.setField(pg, "pgSideProcessing", true);
        ReflectionTestUtils.setField(pg, "contractConcurrency", contract);
        return pg;
    }

    private static PgApproveCommand command(String paymentKey, String idempotencyKey) {
        return new PgApproveCommand(paymentKey, "order-" + paymentKey, 10_000, 0, idempotencyKey);
    }

    @Test
    @DisplayName("끊긴 뒤 PG 가 아직 처리 중이면 같은 멱등키는 409(결과 모름)이고 처리가 끝나면 처음 결과를 돌려준다")
    void sameKeyWhileProcessingIsUnknownThenReplays() throws InterruptedException {
        FakePgClient pg = pg(300, 50, 0);

        assertThat(pg.approve(command("k1", "o1:1")).outcome()).isEqualTo(PgOutcome.TIMEOUT);
        PgApproveResult resend = pg.approve(command("k1", "o1:1"));
        assertThat(resend.outcome()).isEqualTo(PgOutcome.TIMEOUT);
        assertThat(resend.failReason()).contains("409");

        Thread.sleep(400);
        assertThat(pg.approve(command("k1", "o1:1")).outcome()).isEqualTo(PgOutcome.SUCCESS);
    }

    @Test
    @DisplayName("PG 쪽 동시 처리 한도는 우리가 끊은 호출도 세고, PG 처리가 끝나면 자리가 난다")
    void contractLimitCountsCallsWeAlreadyCut() throws InterruptedException {
        FakePgClient pg = pg(300, 50, 1);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        pg.registerPgSideMetrics(registry);

        assertThat(pg.approve(command("a", "oa:1")).outcome()).isEqualTo(PgOutcome.TIMEOUT);
        // 우리는 50ms 에 끊었지만 PG 는 300ms 동안 a 를 처리 중이라 b 는 한도에 걸린다
        PgApproveResult b = pg.approve(command("b", "ob:1"));
        assertThat(b.outcome()).isEqualTo(PgOutcome.FAILED);
        assertThat(pg.query("b").status()).isEqualTo(PgPaymentStatus.NOT_FOUND);

        Thread.sleep(400);
        assertThat(pg.approve(command("c", "oc:1")).outcome()).isEqualTo(PgOutcome.TIMEOUT);
        assertThat(registry.get("fake.pg.contract.rejected").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("fake.pg.side.inflight.max").gauge().value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("느린 꼬리 비율이 1 이면 모든 승인이 느린 지연을 받는다")
    void slowTailRateAppliesSlowLatency() {
        FakePgClient pg = pg(0, 50, 0);
        ReflectionTestUtils.setField(pg, "approveSlowRate", 1.0);
        ReflectionTestUtils.setField(pg, "approveSlowLatencyMs", 200L);

        assertThat(pg.approve(command("s", "os:1")).outcome()).isEqualTo(PgOutcome.TIMEOUT);
    }

    @Test
    @DisplayName("스위치를 켜지 않으면 같은 결제키를 다시 보내도 매번 새로 처리한다(지금 동작 그대로)")
    void offByDefault() {
        FakePgClient pg = new FakePgClient();
        assertThat(pg.approve(command("d", "od:1")).outcome()).isEqualTo(PgOutcome.SUCCESS);
        assertThat(pg.approve(command("d", "od:1")).outcome()).isEqualTo(PgOutcome.SUCCESS);
    }
}
