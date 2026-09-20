package com.beomsu.becommerce.personalization.internal;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

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
 */
@Component
public class PersonalizationMetrics {

    private final Counter contextFailOpen;
    private final Timer contextApply;

    public PersonalizationMetrics(MeterRegistry registry) {
        this.contextFailOpen = Counter.builder("personalization.context.fail_open")
                .description("컨텍스트 저장소 읽기 실패로 빈 컨텍스트로 폴백한 횟수")
                .register(registry);
        this.contextApply = Timer.builder("personalization.context.apply")
                .description("이벤트 하나를 컨텍스트에 적용하는 데 걸린 시간")
                .register(registry);
    }

    /** 저장소 읽기 실패로 폴백했다. 이 값이 오르면 개인화가 조용히 꺼지고 있다는 뜻이다. */
    public void contextFailOpen() {
        contextFailOpen.increment();
    }

    public Timer contextApplyTimer() {
        return contextApply;
    }
}
