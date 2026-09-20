package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.reconciliation.ReconciliationResolvedEvent;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Duration;
import java.time.Instant;

/**
 * 사람이 확정하면 제안과 맞춰 본다. <b>채택률·소요 시간·blind 비교</b>를 남긴다.
 *
 * <p><b>채택률 하나만 재지 않는 이유.</b> 채택률은 "작업이 깨끗하게 끝났는지"를 답하지
 * 못한다. 높은데 재작업과 검토 시간도 같이 높으면 낮은 품질을 받아들이고 있다는 뜻이다.
 * 게다가 사람이 제안을 <b>보고</b> 확정하므로 앵커링이 섞인다. LLM 심판에서도 첫 제안에
 * 끌려가는 계수가 사람 위원회보다 높게 관측된다.
 *
 * <p>그래서 셋을 같이 남긴다.
 *
 * <ul>
 *   <li>{@code assist.residual.accepted} — 제안한 원인과 사람이 고른 원인이 같았나</li>
 *   <li>{@code assist.residual.resolve.latency} — 제안을 받은 뒤 확정까지 걸린 시간</li>
 *   <li>{@code blind} 태그 — 제안을 감춘 표본인지. <b>이게 비교군이다</b></li>
 * </ul>
 *
 * <p><b>blind 표본이 요점이다.</b> 제안을 보여준 건만 모으면 "제안 덕분에 빨라졌다"와
 * "원래 그 정도였다"를 가를 수 없다. 일부를 감춰야 비교가 성립한다. 감춘 건에도 모델은
 * 돌려서 <b>보여줬다면 맞았을지</b>를 같이 기록한다.
 *
 * <p>업무를 막지 않는다. 커밋 뒤에 듣고, 실패해도 확정은 그대로 끝난다.
 */
@Slf4j
@Component
public class ResidualAcceptanceRecorder {

    private final ResidualSuggestionLog suggestions;
    private final MeterRegistry registry;
    private final SuggestionOutcomeRepository outcomes;

    ResidualAcceptanceRecorder(ResidualSuggestionLog suggestions, MeterRegistry registry,
                               SuggestionOutcomeRepository outcomes) {
        this.suggestions = suggestions;
        this.registry = registry;
        this.outcomes = outcomes;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(ReconciliationResolvedEvent e) {
        try {
            var entry = suggestions.take(e.reconResultId());
            if (entry.isEmpty()) {
                // 화면이 후보를 안 불렀거나 기권했거나 기록이 만료됐다.
                registry.counter("assist.residual.accepted", "outcome", "no_suggestion",
                        "blind", "-").increment();
                persist(e, null, SuggestionOutcome.Outcome.NO_SUGGESTION, false, null);
                return;
            }
            ResidualSuggestionLog.Entry s = entry.get();
            String blind = s.shown() ? "false" : "true";
            String outcome = s.cause() == null ? "abstained"
                    : s.cause().name().equals(e.chosenCause()) ? "accepted" : "rejected";

            registry.counter("assist.residual.accepted", "outcome", outcome, "blind", blind)
                    .increment();
            registry.timer("assist.residual.resolve.latency", "blind", blind)
                    .record(Duration.between(s.at(), e.resolvedAt() == null ? Instant.now() : e.resolvedAt()));

            // 지표만으로는 부족하다. 재시작하면 사라지고 유형별로 안 갈려서,
            // 자동 확정 조건("그 유형의 실측 오류율")을 계산할 수가 없다.
            persist(e, s.cause() == null ? null : s.cause().name(),
                    SuggestionOutcome.Outcome.valueOf(outcome.toUpperCase()), !s.shown(), s.at());

            log.info("[residual-accept] recon={} blind={} suggested={} chosen={} outcome={}",
                    e.reconResultId(), blind, s.cause(), e.chosenCause(), outcome);
        } catch (RuntimeException ex) {
            log.warn("[residual-accept] 집계 실패 recon={}", e.reconResultId(), ex);
        }
    }

    /**
     * 행으로 남긴다. <b>집계에 실패해도 확정은 이미 끝났다</b> — 여기서 던지면 되돌릴 것도
     * 없는데 로그만 시끄러워진다. 같은 대사 건이 두 번 오면 유니크 제약이 막는다.
     */
    private void persist(ReconciliationResolvedEvent e, String suggested,
                         SuggestionOutcome.Outcome outcome, boolean blind, Instant suggestedAt) {
        try {
            if (outcomes.existsByReconResultId(e.reconResultId())) {
                return;
            }
            outcomes.save(SuggestionOutcome.of(e.reconResultId(), suggested, e.chosenCause(),
                    outcome, blind, e.actor(), suggestedAt, e.resolvedAt()));
        } catch (RuntimeException ex) {
            log.warn("[residual-accept] 승인 기록 저장 실패 recon={}", e.reconResultId(), ex);
        }
    }
}
