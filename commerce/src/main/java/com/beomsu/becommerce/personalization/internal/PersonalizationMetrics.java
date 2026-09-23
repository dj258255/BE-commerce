package com.beomsu.becommerce.personalization.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 개인화 모듈의 지표.
 *
 * <p><b>fail_open 카운터가 있는 이유</b>: 컨텍스트 읽기는 저장소 장애 시 빈 컨텍스트로 폴백한다
 * (온라인 경로는 죽지 않는다 — {@code TokenStore.isRevoked}·{@code QueueService.status}와 같은 규칙).
 * 그런데 조용히 꺼지는 것은 위험하다. 개인화가 통째로 사라진 상태와 정상 상태가 지표로 구분되지
 * 않으면 알림을 걸 수 없다. 그래서 폴백할 때마다 센다({@code RedisVelocityCounter} 패턴).
 *
 * <p>{@code context.apply} 타이머는 실험의 구간 분해에 쓴다 — "consumer → 저장소" 구간이 얼마인지가
 * 브로커가 더하는 지연과 별개로 필요하다(E1 리포트의 "실제 출력").
 *
 * <p><b>반영 나이/건수</b>: 활동 반영이 <b>멈춘 것</b>을 잡는다(#215). CDC 로 전달하면 발행 보장의
 * 근거가 아웃박스에서 binlog·커넥터 건강으로 옮겨가는데, 커넥터가 죽어도, 컨슈머 빈이 빠져도,
 * 브로커가 멈춰도 <b>셋 다 RUNNING·정상으로 보인다</b>. 그래서 원인이 아니라 증상 쪽을 잰다 —
 * *활동이 컨텍스트에 닿고 있는가*({@link #contextApplied()}).
 */
@Component
public class PersonalizationMetrics {

    private final Counter contextFailOpen;
    private final Timer contextApply;
    private final Counter contextApplied;
    /** 0 = 아직 한 번도 반영된 적이 없다(게이지가 그 구간에서 0을 내보낸다). */
    private final AtomicLong lastAppliedAtMillis = new AtomicLong(0);

    public PersonalizationMetrics(MeterRegistry registry) {
        this.contextFailOpen = Counter.builder("personalization.context.fail_open")
                .description("컨텍스트 저장소 읽기 실패로 빈 컨텍스트로 폴백한 횟수")
                .register(registry);
        this.contextApply = Timer.builder("personalization.context.apply")
                .description("이벤트 하나를 컨텍스트에 적용하는 데 걸린 시간")
                .register(registry);

        // 건수 — 나이 게이지만으로는 "느려졌는가"를 못 본다. 비율로 보려고 함께 둔다.
        this.contextApplied = Counter.builder("personalization.context.applied")
                .description("컨텍스트에 반영된 활동 수(적용 호출 기준). 나이 게이지와 짝이다")
                .register(registry);

        // 나이 — 개수가 아니라 나이다. 개수는 "밀리는 중"(정상)과 "멈춤"(사고)을 구분하지 못한다.
        // outbox_pending_oldest_age_seconds·payment_unknown_oldest_age_seconds 와 같은 결이다.
        Gauge.builder("personalization.context.last.applied.age", lastAppliedAtMillis,
                        PersonalizationMetrics::ageSeconds)
                .description("마지막 반영 이후 경과(초). 개수가 아니라 나이다 — 밀리는 중과 멈춤을 가른다")
                .baseUnit("seconds")
                .register(registry);
    }

    /** 저장소 읽기 실패로 폴백했다. 이 값이 오르면 개인화가 조용히 꺼지고 있다는 뜻이다. */
    public void contextFailOpen() {
        contextFailOpen.increment();
    }

    public Timer contextApplyTimer() {
        return contextApply;
    }

    /**
     * 컨텍스트 반영이 한 번 성공했다 — 카운터를 올리고 <b>나이의 기준 시각</b>을 지금으로 옮긴다.
     * 커넥터·컨슈머·브로커 중 무엇이 죽어도 이 호출이 멈추는 것으로만 드러난다(#215).
     */
    public void contextApplied() {
        contextApplied.increment();
        lastAppliedAtMillis.set(System.currentTimeMillis());
    }

    /**
     * 마지막 반영 이후 경과(초).
     *
     * <p><b>한 번도 반영된 적이 없으면 0을 내보낸다.</b> 기동 직후의 값은 "나이"로 의미가 없고, 그대로
     * 큰 수를 내보내면 경보식({@code > 임계값})이 첫 반영 전에 오발화한다. 0은 어떤 임계값보다 작으므로
     * 그 구간에서는 절대 울리지 않는다. 대가는 기동 후 한 번도 반영하지 못한 상태를 이 게이지가
     * 잡지 못한다는 것이고, 그건 경보 규칙 주석에 적어 두었다.
     */
    private static double ageSeconds(AtomicLong lastApplied) {
        long last = lastApplied.get();
        if (last == 0) {
            return 0;
        }
        return (System.currentTimeMillis() - last) / 1000.0;
    }
}
