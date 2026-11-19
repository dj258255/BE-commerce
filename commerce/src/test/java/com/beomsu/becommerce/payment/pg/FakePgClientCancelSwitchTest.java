package com.beomsu.becommerce.payment.pg;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 승인 · 매입 실험(#476)용 가짜 PG 스위치: 취소 API 장애와 매입 마감. 실험이 이 스위치를 믿고 건수를 읽으므로 동작을 고정한다.
 */
class FakePgClientCancelSwitchTest {

    private static PgCancelCommand cancel(String key) {
        return new PgCancelCommand(key, 10_000, "재고 부족", 1);
    }

    @Test
    @DisplayName("취소 장애 동안은 연결 실패로 끝나고 PG 측 승인은 그대로 남는다. 장애가 끝나면 취소된다")
    void cancelOutageThenRecovers() {
        FakePgClient pg = new FakePgClient();
        ReflectionTestUtils.setField(pg, "cancelFailForMs", 180_000L);
        pg.approve(new PgApproveCommand("ac-1", "ord-1", 10_000));

        assertThatThrownBy(() -> pg.cancel(cancel("ac-1")))
                .isInstanceOf(ResourceAccessException.class)
                .satisfies(e -> assertThat(PgUnreachableException.isConnectFailure(e)).isTrue());
        assertThat(pg.query("ac-1").status()).isEqualTo(PgPaymentStatus.APPROVED);

        ((AtomicLong) ReflectionTestUtils.getField(pg, "firstCancelFailAt")).addAndGet(-180_000L);
        pg.cancel(cancel("ac-1"));
        assertThat(pg.query("ac-1").status()).isEqualTo(PgPaymentStatus.CANCELED);
    }

    @Test
    @DisplayName("마감 전에 승인된 건이 마감 뒤 취소되면 매입 취소, 마감 전 취소와 마감 뒤 승인 건은 매입 전 취소로 센다")
    void cancelAfterCutoffCountsAsAfterCapture() {
        FakePgClient pg = new FakePgClient();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        pg.registerMetrics(registry);
        ReflectionTestUtils.setField(pg, "captureCutoffAfterMs", 60_000L);

        pg.approve(new PgApproveCommand("ac-early", "ord-1", 10_000));
        pg.approve(new PgApproveCommand("ac-before", "ord-2", 10_000));
        pg.cancel(cancel("ac-before"));                       // 마감 전 취소

        // 마감을 지나게 첫 승인 시각을 61초 당긴다. 이미 승인된 두 건의 승인 시각도 같이 당긴다
        ((AtomicLong) ReflectionTestUtils.getField(pg, "firstApprovedAt")).addAndGet(-61_000L);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Long> at = (java.util.Map<String, Long>) ReflectionTestUtils.getField(pg, "approvedAtMs");
        at.replaceAll((k, v) -> v - 61_000L);

        pg.cancel(cancel("ac-early"));                        // 마감 전 승인, 마감 뒤 취소
        pg.approve(new PgApproveCommand("ac-next-day", "ord-3", 10_000));
        pg.cancel(cancel("ac-next-day"));                     // 마감 뒤 승인, 같은 날 취소

        assertThat(registry.get("fake.pg.cancel.after.capture").functionCounter().count()).isEqualTo(1.0);
        assertThat(registry.get("fake.pg.cancel.before.capture").functionCounter().count()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("스위치를 켜지 않으면 취소는 늘 성공하고 매입 취소로 세지 않는다")
    void defaultsAreOff() {
        FakePgClient pg = new FakePgClient();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        pg.registerMetrics(registry);
        pg.approve(new PgApproveCommand("ac-1", "ord-1", 10_000));
        pg.cancel(cancel("ac-1"));
        assertThat(pg.query("ac-1").status()).isEqualTo(PgPaymentStatus.CANCELED);
        assertThat(registry.get("fake.pg.cancel.after.capture").functionCounter().count()).isZero();
    }
}
