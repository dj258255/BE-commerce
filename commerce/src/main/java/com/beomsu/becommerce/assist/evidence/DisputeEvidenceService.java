package com.beomsu.becommerce.assist.evidence;

import com.beomsu.becommerce.assist.draft.AmountCoverageGuard;
import com.beomsu.becommerce.assist.draft.DraftService;
import com.beomsu.becommerce.assist.draft.FactPack;
import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.assist.narrative.TimelineNarrativePort;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 이의제기 증빙을 모아 준다. <b>제출은 사람이 한다.</b>
 *
 * <p><b>왜 자동 제출을 안 하나</b>: 카드사에 낸 증빙은 되돌릴 수 없고, 한 번 부실하게 내면
 * 그 건을 지는 것으로 끝나지 않는다. 그리고 이 시스템은 아직 <b>승소율을 재 본 적이 없다</b> —
 * 실측 없이 자동화하지 않는다는 순서를 여기서도 지킨다.
 *
 * <p><b>두 층으로 만든다.</b>
 * <ul>
 *   <li>항목 조립은 <b>규칙</b>이다. 어느 줄이 어느 항목인지는 출처가 이미 말해 준다</li>
 *   <li>요약 문장만 <b>모델</b>이다. 흩어진 사실을 하나의 주장으로 엮는 자리라
 *       규칙이 문장을 못 쓴다(18 문서에서 서술을 켠 것과 같은 논리)</li>
 * </ul>
 *
 * <p><b>모델이 실패해도 증빙은 나간다.</b> 요약이 없으면 항목만 나가고, 그건 사람이 손으로
 * 쓰던 것보다 이미 낫다. 요약을 못 만든다고 증빙 전체를 버리면 기능이 없는 것과 같다.
 *
 * <p><b>가드를 그대로 건다.</b> 지어낸 숫자가 섞인 증빙과 금액이 빠진 증빙은 둘 다 못 쓴다.
 * 앞엣것은 카드사가 잡으면 이후 다툼의 신뢰를 잃고, 뒤엣것은 안 낸 것과 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DisputeEvidenceService {

    private final DraftService draftService;
    private final DisputeEvidenceAssembler assembler;
    private final NumericProvenanceGuard numberGuard;
    private final AmountCoverageGuard coverageGuard;
    private final Optional<TimelineNarrativePort> narrator;
    private final MeterRegistry registry;

    /** 알림·대시보드가 보는 이름. {@code assist.residual.outcome} 과 규칙을 맞춘다. */
    public static final String METRIC = "dispute.evidence.outcome";

    /**
     * 주문 하나의 증빙을 모은다.
     *
     * @return 사실이 없으면 empty — 없는 것으로 증빙을 만들지 않는다
     */
    @Transactional(readOnly = true)
    public Optional<DisputeEvidence> assemble(String orderNo) {
        FactPack facts = draftService.factsFor(orderNo, null);
        if (facts.facts().isEmpty()) {
            count("no_facts");
            return Optional.empty();
        }

        String narrative = narrateOrNull(facts, orderNo);
        DisputeEvidence evidence = assembler.assemble(facts, narrative);

        count(evidence.gaps().isEmpty() ? "complete" : "has_gaps");
        if (!evidence.gaps().isEmpty()) {
            // 비어 있는 항목을 로그로도 남긴다. 증빙의 약점을 아는 채로 내야 한다.
            log.info("[dispute-evidence] 못 채운 항목 order={} gaps={}", orderNo, evidence.gaps());
        }
        return Optional.of(evidence);
    }

    /** 요약은 있으면 좋은 것이다. 못 만들면 {@code null} 이고 항목만 나간다. */
    private String narrateOrNull(FactPack facts, String orderNo) {
        if (narrator.isEmpty()) {
            return null;
        }
        Optional<String> raw = narrator.get().narrate(facts);
        if (raw.isEmpty()) {
            count("narrative_abstained");
            return null;
        }
        List<String> unsourced = numberGuard.verify(raw.get(), facts);
        if (!unsourced.isEmpty()) {
            count("narrative_unsourced");
            log.info("[dispute-evidence] 출처 없는 숫자가 있어 요약을 버림 order={} figures={}",
                    orderNo, unsourced);
            return null;
        }
        List<Long> dropped = coverageGuard.missing(raw.get(), facts);
        if (!dropped.isEmpty()) {
            count("narrative_dropped_amounts");
            log.info("[dispute-evidence] 금액을 빠뜨려 요약을 버림 order={} amounts={}", orderNo, dropped);
            return null;
        }
        return raw.get();
    }

    private void count(String outcome) {
        registry.counter(METRIC, "outcome", outcome).increment();
    }
}
