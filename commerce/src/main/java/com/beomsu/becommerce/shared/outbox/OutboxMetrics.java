package com.beomsu.becommerce.shared.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * 아웃박스(event_publication) 적체 게이지.
 *
 * <p><b>왜 필요한가</b>: {@code @ApplicationModuleListener}는 커밋 이후 <b>별도 스레드</b>에서 돈다.
 * 즉 결제 응답이 200으로 나간 뒤에도 정산·원장·알림·에스크로는 아직 처리되지 않았을 수 있다.
 * 이 시차는 설계상 의도된 것이지만, <b>얼마나 벌어져 있는지 보이지 않으면</b> 정상적인 적체와
 * 소비가 멈춘 상태를 구분할 수 없다. 둘 다 겉으로는 "결제는 잘 되는데 원장이 비어 있다"로 보인다.
 *
 * <p><b>개수보다 나이가 중요하다.</b> 부하 중에는 미완료가 수천 건이어도 정상이다(뒤에서 빠지는 중).
 * 실제로 다계정 스파이크 실험에서 부하 직후 22,492건이 미완료였다가 몇 분 만에 6,174건으로 빠졌다 —
 * 개수만 보면 사고 같지만 정상이었다. 반대로 미완료가 <b>10건뿐이어도 30분째 그대로</b>면 사고다.
 * 그래서 알람은 {@code oldest.age}에 건다. 미확정 결제에 {@code payment.unknown.oldest.age}를
 * 붙인 것과 같은 판단이다.
 *
 * <p>완료분은 {@code event_publication_archive}로 빠지므로(V24, completion-mode=archive)
 * 여기서 세는 값은 <b>처리 대기분</b>과 사실상 같다.
 */
@Component
public class OutboxMetrics {

    private final JdbcTemplate jdbcTemplate;

    public OutboxMetrics(MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;

        Gauge.builder("outbox.pending.count", this, OutboxMetrics::pendingCount)
                .description("아직 소비되지 않은 이벤트 발행 수. 부하 중 증가는 정상이며, "
                        + "줄지 않고 유지되는 것이 이상이다")
                .register(meterRegistry);

        Gauge.builder("outbox.pending.oldest.age", this, OutboxMetrics::oldestPendingAgeSeconds)
                .description("가장 오래된 미소비 이벤트의 나이(초). 알람은 개수가 아니라 이 값에 건다")
                .baseUnit("seconds")
                .register(meterRegistry);
    }

    /** 스크레이프마다 집계 1회. idx_event_pub_incomplete(completion_date, publication_date)를 탄다. */
    double pendingCount() {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from event_publication where completion_date is null", Long.class);
        return count == null ? 0 : count;
    }

    /**
     * 가장 오래된 미소비 이벤트의 나이(초). 없으면 0 — "적체 없음"을 0으로 표현해야
     * 알람 임계식({@code > N})이 빈 상태에서 오발화하지 않는다.
     */
    double oldestPendingAgeSeconds() {
        Instant oldest = jdbcTemplate.queryForObject(
                "select min(publication_date) from event_publication where completion_date is null",
                Instant.class);
        return oldest == null ? 0 : Duration.between(oldest, Instant.now()).toSeconds();
    }
}
