package com.beomsu.becommerce.personalization.internal;

import com.beomsu.becommerce.personalization.UserActivityEvent;
import org.springframework.stereotype.Component;

/**
 * 컨텍스트 적용 — <b>전달 방식이 공유하는 단 하나의 적용 로직</b>.
 *
 * <p>전달 방식 셋(KAFKA·IN_PROCESS·IN_REQUEST)이 이 메서드를 부른다. 그래야 E1-b의 비교에서
 * "누가 언제 부르는가"만 변수로 남고 적용 로직은 상수가 된다. 전달 방식마다 적용을 따로 구현하면
 * 재는 것이 전달 방식이 아니라 구현 차이가 된다.
 *
 * <p>적용 시간을 타이머로 남긴다 — 브로커가 더하는 지연(E1-b)과 "consumer → 저장소" 구간(E1-a)을
 * 분리해서 보려면 이 값이 필요하다.
 */
@Component
public class ContextApplier {

    private final ContextStore store;
    private final PersonalizationMetrics metrics;

    public ContextApplier(ContextStore store, PersonalizationMetrics metrics) {
        this.store = store;
        this.metrics = metrics;
    }

    public OnlineContext apply(UserActivityEvent event) {
        return metrics.contextApplyTimer().record(() -> store.apply(event));
    }
}
