package com.beomsu.becommerce.assist.draft;

import com.beomsu.becommerce.reconciliation.cause.ClassifierAccuracyMetrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import com.beomsu.becommerce.reconciliation.ReconciliationResolvedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 섀도 모드 — 초안을 만들되 <b>사람에게 보여주지 않고 기록만</b> 한다 (ADR-014).
 *
 * <p><b>왜 섀도인가</b>: 초안 품질을 재려면 "사람이 얼마나 고쳤나"를 알아야 하는데,
 * 초안을 보여주면 사람이 그것에 끌려간다(앵커링). 그러면 "고칠 게 없었다"와
 * "고칠 생각이 안 났다"가 구분되지 않는다. 이건 대사 분류기에서 이미 겪고 있는 문제로,
 * {@code ClassifierAccuracyMetrics} 가 자기 수치를 정확도가 아니라 "일치율"이라고
 * 부르는 이유가 그것이다.
 *
 * <p>섀도는 그 고리를 끊는다. 사람은 지금처럼 규칙 제안만 보고 확정하고, 초안은 옆에서
 * 조용히 만들어져 기록된다. <b>정답을 지어내지 않고도 대조가 가능해진다</b> —
 * 사람의 확정이 정답이고, 초안은 그걸 못 본 상태에서 나온 것이니까.
 * 홀드아웃(Stripe)과 selective-labels 대응(Feedzai)이 같은 발상이다.
 *
 * <p><b>업무를 막지 않는다.</b> 초안 생성이 실패하거나 느려도 확정은 그대로 진행된다.
 * 관측을 위해 업무가 멈추면 그 관측은 꺼지게 되어 있다.
 *
 * <p>{@code app.assist.shadow-enabled=true} 일 때만 동작한다. 기본은 꺼짐 —
 * 초안 생성이 타임라인 조립(10개 도메인 조회)을 태우므로 확정마다 비용이 붙는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "app.assist.shadow-enabled", havingValue = "true")
public class ShadowDraftRecorder {

    private final DraftService draftService;
    private final MeterRegistry registry;
    private final Map<String, Counter> counters = new ConcurrentHashMap<>();

    ShadowDraftRecorder(DraftService draftService, MeterRegistry registry) {
        this.draftService = draftService;
        this.registry = registry;
    }

    /**
     * 사람이 확정하면 초안을 만들어 기록한다.
     *
     * <p><b>커밋 뒤에 듣는다.</b> 확정 트랜잭션 안에서 돌면 초안 생성이 느리거나 실패할 때
     * 확정까지 같이 늦어지거나 말려 들어간다. 관측 때문에 업무가 막히면 그 관측은 꺼진다.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onResolved(ReconciliationResolvedEvent e) {
        record(e.orderNo(), e.reconResultId(), e.chosenCause());
    }

    /**
     * 확정 직전에 초안을 만들어 기록한다. <b>반환하지 않는다</b> — 호출자가 화면에
     * 실을 수 없게 한다. 돌려주면 언젠가 누군가 "참고용으로만" 띄운다.
     *
     * @param orderNo       대상 주문
     * @param reconResultId 대사 결과 id
     * @param chosenCause   사람이 실제로 고른 원인. <b>정답 라벨이다</b>
     */
    public void record(String orderNo, Long reconResultId, String chosenCause) {
        Timer.Sample sample = Timer.start(registry);
        try {
            CsDraft draft = draftService.draftFor(orderNo, reconResultId);
            String outcome = draft.text() != null ? "drafted"
                    : draft.verified() ? "no_facts" : "rejected";

            count("assist.shadow.draft", outcome, draft.source(), chosenCause);

            // 본문은 로그에만 남긴다. 나중에 사람의 확정과 대조할 때 필요하다.
            // 지금은 저장소를 따로 두지 않는다 — 표본이 쌓이기 전에 스키마를 정하면
            // 무엇을 재야 하는지 모르는 채로 모양부터 굳는다.
            log.info("[shadow] order={} recon={} outcome={} source={} chosen={} rejected={}",
                    orderNo, reconResultId, outcome, draft.source(), chosenCause, draft.rejected());
            if (draft.text() != null) {
                log.debug("[shadow] order={} draft=\n{}", orderNo, draft.text());
            }
        } catch (RuntimeException e) {
            count("assist.shadow.draft", "error", "-", chosenCause);
            log.warn("[shadow] 초안 생성 실패 order={} recon={}", orderNo, reconResultId, e);
        } finally {
            sample.stop(registry.timer("assist.shadow.latency"));
        }
    }

    private void count(String name, String outcome, String source, String chosenCause) {
        counters.computeIfAbsent(name + "|" + outcome + "|" + source + "|" + chosenCause,
                k -> Counter.builder(name)
                        .tag("outcome", outcome)
                        .tag("source", source)
                        .tag("chosen_cause", chosenCause == null ? "unknown" : chosenCause)
                        .register(registry))
                .increment();
    }
}
