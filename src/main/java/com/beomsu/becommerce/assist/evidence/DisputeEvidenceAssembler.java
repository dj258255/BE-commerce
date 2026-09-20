package com.beomsu.becommerce.assist.evidence;

import com.beomsu.becommerce.assist.draft.FactPack;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 타임라인 사실을 <b>증빙 항목으로 가른다</b>. 규칙이다 — 모델을 안 부른다.
 *
 * <p><b>왜 규칙인가</b>: 어느 줄이 어느 항목에 속하는지는 그 줄의 출처가 이미 말해 준다.
 * {@code PAYMENT} 로 시작하는 줄은 결제 사실이고 {@code ESCROW} 는 이행 사실이다.
 * 추론할 것이 없는 자리에 모델을 부르면 지연과 오류만 늘어난다.
 *
 * <p><b>못 채운 항목을 남긴다.</b> 항목이 비면 그 자리를 빈 채로 두고 {@code gaps} 에 이름을
 * 올린다. 없는 사실을 그럴듯한 문장으로 메우면 그게 증빙의 가장 큰 위험이다 — 카드사가 한 번
 * 잡으면 그 건만 지는 게 아니라 이후 다툼의 신뢰를 잃는다.
 *
 * <p>항목 구성은 카드사 증빙 요건에서 공통으로 요구되는 것들을 따랐다. 거래가 실제로 있었는지,
 * 고객이 받았는지, 환불·취소가 있었는지, 이전에 같은 다툼이 있었는지다.
 */
@Component
public class DisputeEvidenceAssembler {

    /** 항목 이름과, 그 항목으로 가는 타임라인 출처. 순서가 곧 증빙에 실리는 순서다. */
    private static final Map<String, List<String>> SECTIONS = new LinkedHashMap<>();

    static {
        SECTIONS.put("거래 성립", List.of("ORDER", "PAYMENT"));
        SECTIONS.put("대금 흐름", List.of("LEDGER", "SETTLEMENT"));
        SECTIONS.put("이행 증빙", List.of("ESCROW"));
        SECTIONS.put("환불·취소 이력", List.of("POINT", "WALLET"));
        SECTIONS.put("대사 결과", List.of("RECONCILIATION"));
        SECTIONS.put("이전 분쟁", List.of("DISPUTE"));
        SECTIONS.put("운영자 조치", List.of("AUDIT"));
    }

    /**
     * 사실 묶음을 항목으로 가른다.
     *
     * @param facts     타임라인 사실. {@code 날짜 · 출처 · 요약} 모양이다
     * @param narrative 사람이 읽는 요약. 없으면 {@code null}
     */
    public DisputeEvidence assemble(FactPack facts, String narrative) {
        List<DisputeEvidence.Section> sections = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        for (Map.Entry<String, List<String>> e : SECTIONS.entrySet()) {
            List<String> lines = facts.facts().stream()
                    .filter(line -> e.getValue().stream().anyMatch(src -> belongsTo(line, src)))
                    .toList();
            sections.add(new DisputeEvidence.Section(e.getKey(), lines));
            if (lines.isEmpty()) {
                gaps.add(e.getKey());
            }
        }
        return new DisputeEvidence(facts.orderNo(), List.copyOf(sections), narrative, List.copyOf(gaps));
    }

    /**
     * 그 줄이 이 출처의 것인가.
     *
     * <p>가운데 칸만 본다. 요약 문장에 {@code PAYMENT} 라는 낱말이 들어갔다고 결제 사실이
     * 되는 것은 아니다. 단순 {@code contains} 로 하면 그 착각이 조용히 섞인다.
     */
    private boolean belongsTo(String line, String source) {
        String[] parts = line.split("·", 3);
        return parts.length >= 2 && parts[1].strip().equals(source);
    }
}
