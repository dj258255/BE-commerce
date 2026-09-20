package com.beomsu.becommerce.assist.narrative;

import com.beomsu.becommerce.assist.draft.DraftService;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.AmountCoverageGuard;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 운영자용 서술을 만들고 <b>숫자 출처를 검증</b>한다.
 *
 * <p><b>가드가 하나뿐인 이유</b>: 이 글은 사내 화면에만 뜬다. 고객에게 나가지 않으므로 내부 용어
 * 누출도, 말투도 문제가 아니다. 상태를 바꾸지도 않는다. <b>남는 위험은 없는 숫자를 만드는 것
 * 하나</b>라, 가드도 그 하나만 둔다. 가드를 필요 없는 곳에 늘리면 무엇이 왜 있는지 흐려진다.
 *
 * <p><b>버린다, 고치지 않는다.</b> 출처 없는 숫자가 하나라도 있으면 문단을 통째로 버린다.
 * 틀린 숫자가 섞인 서술은 <b>없는 것보다 나쁘다</b> — 운영자가 그걸 근거로 확정하면 틀린 판단이
 * 장부에 남는다(상황 13에서 같은 이유로 조회 실패를 조용히 넘기지 않기로 했다).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TimelineNarrativeService {

    /**
     * 알림이 보는 이름. <b>{@code assist.residual.outcome} 과 규칙을 맞춘다</b> —
     * 마이크로미터는 {@code assist.narrative} 를 {@code assist_narrative_total} 로 내보내지
     * {@code assist_narrative_outcome_total} 로 내보내지 않는다. {@code outcome} 이 이름에
     * 들어가려면 태그가 아니라 이름 자체에 있어야 한다.
     */
    public static final String METRIC = "assist.narrative.outcome";

    private final DraftService draftService;
    private final TimelineNarrativePort port;
    private final NumericProvenanceGuard numberGuard;
    private final AmountCoverageGuard coverageGuard;
    /** 모델 서술이 가드에 걸렸을 때 대신 낼 것. 빈 화면보다 낫다. */
    private final TemplateNarrativeAdapter fallback = new TemplateNarrativeAdapter();
    private final MeterRegistry registry;
    private final NarrativeAuditRepository auditRepository;

    /**
     * 주문 하나를 한 문단으로 엮는다.
     *
     * @return 서술. 사실이 없거나 모델이 기권했거나 <b>출처 없는 숫자가 섞였으면</b> empty
     */
    public Optional<Narrative> narrate(String orderNo) {
        FactPack facts = draftService.factsFor(orderNo, null);
        if (facts.facts().isEmpty()) {
            record(orderNo, "no_facts", null, facts);
            return Optional.empty();
        }

        Optional<String> accepted = pass(port, facts, orderNo);
        if (accepted.isPresent()) {
            record(orderNo, "narrated", accepted.get(), facts);
            // 무엇이 쓴 문장인지 함께 낸다 — 사람이 템플릿과 모델을 구별할 수 있어야 한다.
            return Optional.of(new Narrative(accepted.get(), port.name(), facts.complete()));
        }

        // 여기부터가 폴백이다. 아래 주석의 이유로, 버렸으면 빈손으로 두지 않는다.
        if (port instanceof TemplateNarrativeAdapter) {
            return Optional.empty();   // 이미 템플릿이었으면 더 물러설 곳이 없다
        }
        Optional<String> byTemplate = pass(fallback, facts, orderNo);
        if (byTemplate.isEmpty()) {
            return Optional.empty();
        }
        record(orderNo, "fell_back_to_template", byTemplate.get(), facts);
        log.info("[narrative] 모델 서술을 버리고 템플릿으로 떨어뜨림 order={}", orderNo);
        return Optional.of(new Narrative(byTemplate.get(), fallback.name(), facts.complete()));
    }

    /**
     * 한 구현의 출력을 가드에 태워 통과한 것만 돌려준다.
     *
     * <p><b>왜 폴백을 두나</b>: 가드에 걸린 서술을 버리면 운영자 화면이 <b>빈다</b>. 금액 결손은
     * 실측에서 30건 중 0~4건으로 간헐적이라, 모델을 켜면 그만큼의 확률로 요약이 통째로 없는
     * 화면을 보게 된다. 템플릿은 그 자리에서도 늘 뭔가를 낸다.
     *
     * <p>그래서 버렸을 때 빈손 대신 템플릿을 준다. 이렇게 두면 모델을 켜는 최악의 경우가
     * <b>템플릿을 켠 것과 같아진다.</b> 어느 쪽 문장이 더 읽히는지는 아직 못 쟀지만, 못 잰 것이
     * 위험이 되지는 않는 상태가 된다.
     */
    private Optional<String> pass(TimelineNarrativePort candidate, FactPack facts, String orderNo) {
        Optional<String> raw = candidate.narrate(facts);
        if (raw.isEmpty()) {
            record(orderNo, "abstained", null, facts);
            return Optional.empty();
        }
        List<String> unsourced = numberGuard.verify(raw.get(), facts);
        if (!unsourced.isEmpty()) {
            record(orderNo, "unsourced_figures", null, facts);
            log.info("[narrative] 출처 없는 숫자가 있어 서술을 버림 order={} figures={}",
                    orderNo, unsourced);
            return Optional.empty();
        }
        // 반대 방향도 본다 — 지어낸 숫자가 없어도, 있어야 할 금액을 버렸으면 못 쓴다.
        List<Long> dropped = coverageGuard.missing(raw.get(), facts);
        if (!dropped.isEmpty()) {
            record(orderNo, "dropped_amounts", null, facts);
            log.info("[narrative] 금액을 빠뜨려 서술을 버림 order={} amounts={}", orderNo, dropped);
            return Optional.empty();
        }
        return raw;
    }

    /**
     * 지표와 감사 기록을 함께 남긴다. <b>버린 것도 남긴다</b> — 화면이 빈 이유를 나중에
     * 답할 수 있어야 하고, 폐기율 자체가 이 기능의 상태를 말한다.
     *
     * <p>기록에 실패해도 서술은 내보낸다. 감사가 기능을 세우면 그건 다른 사고가 된다.
     */
    private void record(String orderNo, String outcome, String output, FactPack facts) {
        registry.counter(METRIC, "outcome", outcome).increment();
        try {
            auditRepository.save(NarrativeAudit.of(orderNo, port.name(), outcome, output,
                    facts.facts().size(), facts.complete()));
        } catch (RuntimeException e) {
            log.warn("[narrative] 감사 기록 실패 order={} outcome={} : {}", orderNo, outcome, e.toString());
        }
    }

    /**
     * @param text     운영자가 읽을 문단
     * @param source   무엇이 썼는가(템플릿/모델). 화면에 함께 띄운다
     * @param complete 타임라인이 완전했는가. false 면 화면이 그 사실을 같이 알려야 한다
     */
    public record Narrative(String text, String source, boolean complete) {
    }
}
