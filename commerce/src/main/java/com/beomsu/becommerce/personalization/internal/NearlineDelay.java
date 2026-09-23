package com.beomsu.becommerce.personalization.internal;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 근사선 소비 지연 주입 — E1의 "consumer 지연을 바꿔가며" 축이다.
 *
 * <p>실제 부하에서 소비 지연을 만드는 방법은 여럿이다(브로커에 lag을 만들거나, 소비자를 느리게
 * 만들거나). 여기서는 <b>처리 지점에서 재우는</b> 방식을 쓴다 — 재현이 쉽고 값이 결정적이며,
 * "지연이 얼마일 때 무엇이 일어나는가"라는 질문에 정확히 답하기 때문이다.
 *
 * <p>대신 이 방식이 만들지 못하는 것도 분명하다: <b>처리량 부족</b>은 재현하지 않는다(재우는 것은
 * 스레드를 묶지만 큐를 쌓지는 않는다). 처리량 축은 컨슈머 동시성({@code concurrency})으로 따로
 * 열어 두고, 무엇을 쟀는지 리포트의 "환경" 절에 적는다.
 *
 * <p>기본 0이라 평소에는 아무 비용도 없다.
 */
@Component
class NearlineDelay {

    private final long delayMs;

    NearlineDelay(@Value("${app.personalization.consumer.delay-ms:0}") long delayMs) {
        this.delayMs = delayMs;
    }

    /** 실제로 잰 지연을 쓰는 곳이 없으므로 void다. 재우는 것 말고 하는 일이 없다. */
    void apply() {
        if (delayMs <= 0) {
            return;
        }
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
