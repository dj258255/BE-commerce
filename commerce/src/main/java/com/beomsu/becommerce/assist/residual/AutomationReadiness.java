package com.beomsu.becommerce.assist.residual;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 자동 확정으로 올려도 되는 유형이 있는지 <b>재서</b> 답한다.
 *
 * <p><b>왜 이게 필요한가</b>: 자동 확정의 조건을 문서에 셋으로 적어 뒀다. 증거가 결정적일 것,
 * 금액이 임계 미만일 것, <b>그 유형의 실측 오류율이 선언한 한도 안일 것</b>. 그런데 마지막
 * 조건을 <b>계산하는 코드가 없었다.</b> 조건은 글로만 있고 재는 기구가 없으면, 승격은
 * 판단이 아니라 인상이 된다.
 *
 * <p><b>가린 표본만 센다.</b> 제안을 보여준 뒤 고르게 하면 앵커링이 생겨 일치율이 올라간다.
 * 그 수치로 승격을 정하면 <b>모델이 사람을 설득한 결과를 모델의 정확도로 읽는 것</b>이 된다.
 *
 * <p><b>이 서비스는 켜지 않는다.</b> 판정만 낸다. 실제로 올릴지는 사람이 정하고, 올린다면
 * 그것도 코드 변경이어야 한다 — 지표가 좋아졌다고 자동으로 권한이 올라가면 되돌릴 자리가 없다.
 */
@Service
@RequiredArgsConstructor
public class AutomationReadiness {

    private final SuggestionOutcomeRepository repository;

    /** 유형별 최소 표본. 이보다 적으면 비율을 못 믿는다. */
    @Value("${app.assist.automation.min-samples:30}")
    private int minSamples;

    /** 허용 오류율. 이 값을 넘으면 올리지 않는다. */
    @Value("${app.assist.automation.max-error-rate:0.05}")
    private double maxErrorRate;

    /**
     * @param cause      원인 유형
     * @param samples    가린 채로 고른 표본 수(제안이 있었던 건만)
     * @param rejected   사람이 다른 것을 고른 수. <b>오류율의 분자</b>
     * @param errorRate  {@code rejected / samples}
     * @param ready      셋 조건 중 <b>표본과 오류율</b>을 통과했나. 증거·금액 조건은 건별이라 여기서 못 본다
     * @param reason     ready 가 아니면 왜 아닌지
     */
    public record Verdict(String cause, long samples, long rejected, double errorRate,
                          boolean ready, String reason) {
    }

    @Transactional(readOnly = true)
    public List<Verdict> assess() {
        // cause → outcome → count (가린 것만)
        Map<String, Map<String, Long>> byCause = new LinkedHashMap<>();
        for (Object[] row : repository.tallyByCause()) {
            boolean blind = (Boolean) row[1];
            if (!blind) {
                continue;   // 보여준 뒤 고른 것은 앵커링이 섞여 승격 판단에 못 쓴다
            }
            byCause.computeIfAbsent((String) row[0], k -> new LinkedHashMap<>())
                    .merge((String) row[2], (Long) row[3], Long::sum);
        }

        List<Verdict> out = new ArrayList<>();
        for (var e : byCause.entrySet()) {
            long accepted = e.getValue().getOrDefault("accepted", 0L);
            long rejected = e.getValue().getOrDefault("rejected", 0L);
            long samples = accepted + rejected;   // 기권은 맞고 틀림이 아니라 분모에서 뺀다
            double rate = samples == 0 ? 1.0 : (double) rejected / samples;

            String reason;
            boolean ready;
            if (samples < minSamples) {
                ready = false;
                reason = "표본 %d건으로 최소 %d건에 못 미친다".formatted(samples, minSamples);
            } else if (rate > maxErrorRate) {
                ready = false;
                reason = "오류율 %.1f%%가 한도 %.1f%%를 넘는다".formatted(rate * 100, maxErrorRate * 100);
            } else {
                ready = true;
                reason = "표본 %d건에서 오류율 %.1f%%. 증거·금액 조건은 건별로 따로 본다"
                        .formatted(samples, rate * 100);
            }
            out.add(new Verdict(e.getKey(), samples, rejected, rate, ready, reason));
        }
        return List.copyOf(out);
    }
}
