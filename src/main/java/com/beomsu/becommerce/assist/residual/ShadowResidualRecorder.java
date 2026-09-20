package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.assist.draft.ShadowDraftRecorder;
import com.beomsu.becommerce.reconciliation.ReconciliationResolvedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.List;
import java.util.Optional;

/**
 * 잔여 후보를 <b>화면에 띄우지 않고</b> 기록만 한다. Ramp 가 거래 재분류 에이전트를
 * 켜기 전에 쓴 방식과 같다. 에이전트가 무엇을 할 것인지만 정하게 두고 실행하지 않은 채
 * 사람의 행동과 대조해 정확도를 누적하다가, 임계를 넘은 뒤에야 실제 개입을 켠다.
 *
 * <p>이 프로젝트에는 그 대조가 가능한 자리가 이미 있다. <b>사람이 원인을 고르는 순간이
 * 곧 라벨</b>이라, 확정 이벤트를 듣고 같은 건에 모델을 돌려 맞춰 보면 된다.
 *
 * <p><b>여기서 나오는 수치는 정확도가 아니다.</b> 섀도 모드라 사람이 제안을 못 보므로
 * 앵커링 편향은 없지만, 표본이 손으로 만든 대사 케이스라 분포가 실제와 다르다.
 * 그래서 "탐색 실행의 일치율"이라고 부른다.
 *
 * <p>{@link ShadowDraftRecorder}와 같은 이유로 커밋 뒤에 듣고, 실패해도 확정을 막지 않는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.assist.residual-shadow-enabled", havingValue = "true")
public class ShadowResidualRecorder {

    private final ResidualCauseService residual;
    private final MeterRegistry registry;

    ShadowResidualRecorder(ResidualCauseService residual, MeterRegistry registry) {
        this.residual = residual;
        this.registry = registry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(ReconciliationResolvedEvent e) {
        try {
            // 규칙 제안을 빈 목록으로 넘긴다. 이 경로는 "규칙이 못 가른 건"을 흉내 내는
            // 것이 목적이고, 규칙이 답한 건은 애초에 ④의 대상이 아니다.
            Optional<ResidualSuggestion> s =
                    residual.suggest(e.orderNo(), e.reconResultId(), List.of());

            if (s.isEmpty()) {
                count("abstained", e.chosenCause());
                log.info("[residual-shadow] 기권 order={} chosen={}", e.orderNo(), e.chosenCause());
                return;
            }
            boolean hit = s.get().cause().name().equals(e.chosenCause());
            count(hit ? "match" : "miss", e.chosenCause());
            log.info("[residual-shadow] order={} predicted={} chosen={} confidence={} match={}",
                    e.orderNo(), s.get().cause(), e.chosenCause(), s.get().confidence(), hit);
        } catch (RuntimeException ex) {
            count("error", e.chosenCause());
            log.warn("[residual-shadow] 실패 order={}", e.orderNo(), ex);
        }
    }

    private void count(String outcome, String chosen) {
        registry.counter("assist.residual.shadow", "outcome", outcome, "chosen", chosen).increment();
    }
}
