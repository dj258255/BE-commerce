package com.beomsu.becommerce.fraud.review;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 사람이 끝낸 심사에서 <b>규칙별 오탐</b>을 센다.
 *
 * <p>대사 쪽에서 이미 한 순환을 이상거래 쪽에 옮긴 것이다. 규칙이 답한다 → 맞히는 비율을
 * 잰다 → 그 수치로 규칙을 손본다. 대사는
 * {@code RulePromotionService}가 사람 손을 규칙으로 <b>올리는</b> 방향이고, 여기는 규칙을
 * <b>내리는</b> 방향이라는 것만 다르다.
 *
 * <p><b>여기서 임계를 자동으로 바꾸지 않는다.</b> 규칙을 조이면 놓치는 부정 거래가 생기고
 * 그건 돈이다. 이 서비스가 하는 일은 <b>"이 규칙을 볼 이유"를 숫자로 만드는 것</b>까지다.
 */
@Service
public class RuleFalsePositiveService {

    private final FraudReviewRepository repository;

    public RuleFalsePositiveService(FraudReviewRepository repository) {
        this.repository = repository;
    }

    /**
     * 판정이 끝난 심사를 규칙별로 갈라 오탐률을 낸다.
     *
     * <p>기준을 넘긴 것만 주지 않고 <b>전부 준다.</b> 넘긴 것만 주면 화면이 비었을 때
     * 규칙이 다 멀쩡한 것인지 표본이 아직 없는 것인지 구분이 안 된다.
     */
    @Transactional(readOnly = true)
    public List<RuleFalsePositive> byRule() {
        Map<String, long[]> tally = new LinkedHashMap<>();   // [오탐, 정탐]

        for (var row : repository.findJudgedReasons()) {
            boolean falsePositive = row.getStatus() == FraudReviewStatus.APPROVED;
            for (String rule : rulesOf(row.getReasons())) {
                long[] counts = tally.computeIfAbsent(rule, k -> new long[2]);
                counts[falsePositive ? 0 : 1]++;
            }
        }

        List<RuleFalsePositive> out = new ArrayList<>();
        tally.forEach((rule, c) -> {
            long judged = c[0] + c[1];
            out.add(new RuleFalsePositive(rule, c[0], c[1], judged == 0 ? 0 : (double) c[0] / judged));
        });

        // 손볼 것부터. 같은 조건이면 판정 표본이 두꺼운 쪽이 먼저다 — 근거가 더 단단하다.
        out.sort(Comparator
                .comparing(RuleFalsePositive::actionable).reversed()
                .thenComparing(Comparator.comparingLong(RuleFalsePositive::judged).reversed()));
        return out;
    }

    /**
     * 근거 문자열에서 <b>규칙 이름만</b> 뽑는다.
     *
     * <p>근거는 {@code "VELOCITY_EXCEEDED(7), HIGH_AMOUNT"} 처럼 저장된다. 괄호 안은 그때의
     * 발동 횟수나 할부 개월이라 건마다 다르다. <b>떼지 않으면 같은 규칙이 값마다 쪼개져</b>
     * 표본이 하나씩 흩어지고, 어느 것도 판정 20건을 못 넘긴다.
     *
     * <p>한 건 안에서 같은 규칙이 두 번 나오면 한 번만 센다. 건수를 세는 것이지 발동
     * 횟수를 세는 것이 아니다.
     */
    private Set<String> rulesOf(String reasons) {
        Set<String> rules = new HashSet<>();
        if (reasons == null || reasons.isBlank()) {
            return rules;
        }
        for (String token : reasons.split(",")) {
            int paren = token.indexOf('(');
            String name = (paren >= 0 ? token.substring(0, paren) : token).trim();
            if (!name.isEmpty()) {
                rules.add(name);
            }
        }
        return rules;
    }
}
