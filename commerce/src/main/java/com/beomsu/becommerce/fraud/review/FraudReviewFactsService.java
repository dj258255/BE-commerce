package com.beomsu.becommerce.fraud.review;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
import com.beomsu.becommerce.fraud.FraudReviewFactsPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 심사 한 건의 사실을 모은다. <b>여기서 만든 값만 모듈 밖으로 나간다.</b>
 *
 * <p>세 곳에서 모은다.
 * <ol>
 *   <li>심사 항목 자체 — 금액·점수·등급·발동 근거</li>
 *   <li>{@link RuleFalsePositiveService} — 발동한 규칙이 최근 심사에서 얼마나 정상으로 닫혔는지</li>
 *   <li>같은 카드의 지난 심사 — 처음 걸린 카드인지 여러 번 걸렸던 카드인지</li>
 * </ol>
 *
 * <p><b>2번이 이 서비스를 만든 이유다.</b> 규칙별 정상 판정 비율은 이미 있었는데
 * {@code /rule-false-positives} 화면에만 있었다. 심사자는 건별 화면에서 판단하므로
 * <b>그 수치를 한 번도 보지 못했다.</b> 같은 값을 심사하는 자리로 옮기는 것이다.
 */
@Service
public class FraudReviewFactsService implements FraudReviewFactsPort {

    private final FraudReviewRepository repository;
    private final RuleFalsePositiveService ruleStats;

    public FraudReviewFactsService(FraudReviewRepository repository, RuleFalsePositiveService ruleStats) {
        this.repository = repository;
        this.ruleStats = ruleStats;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<FraudReviewFacts> factsOf(long reviewId) {
        return repository.findById(reviewId).map(this::assemble);
    }

    private FraudReviewFacts assemble(FraudReview r) {
        Map<String, RuleFalsePositive> stats = ruleStats.byRule().stream()
                .collect(java.util.stream.Collectors.toMap(RuleFalsePositive::rule, Function.identity(),
                        (a, b) -> a));

        List<FraudReviewFacts.FiredRule> fired = new ArrayList<>();
        for (var token : tokensOf(r.getReasons())) {
            RuleFalsePositive s = stats.get(token.name());
            long judged = s == null ? 0 : s.judged();
            // 표본이 얇으면 비율을 아예 안 넘긴다. 넘기면 초안이 그 숫자를 문장에 쓴다.
            Double normal = (s != null && judged >= FraudReviewFacts.MIN_JUDGED_TO_SHOW)
                    ? s.ratio() : null;
            fired.add(new FraudReviewFacts.FiredRule(token.name(), token.detail(), judged, normal));
        }

        long[] sameCard = sameCardTally(r);

        return new FraudReviewFacts(
                r.getId(), r.getOrderNo(), r.getPaymentId(), mask(r.getCardKey()), r.getAmount(),
                r.getScore(), r.getDecision().name(), r.getCreatedAt(), List.copyOf(fired),
                sameCard[0], sameCard[1]);
    }

    /**
     * 같은 카드로 <b>판정이 끝난</b> 심사를 센다. 대기 건은 안 넣는다.
     *
     * <p>이 카드가 전에도 걸렸는지, 그때 사람이 어떻게 닫았는지가 심사자에게 필요한 맥락이다.
     * 세 번 걸려 세 번 다 정상으로 닫혔으면 네 번째도 정상일 가능성이 높고, 반대면 반대다.
     * 지금 건은 아직 판정 전이라 자연히 빠진다.
     *
     * @return {@code [판정 끝난 건수, 그중 정상으로 닫힌 건수]}
     */
    private long[] sameCardTally(FraudReview target) {
        long judged = 0, approved = 0;
        for (FraudReview r : repository.findByCardKey(target.getCardKey())) {
            if (isSame(r, target) || r.getStatus() == FraudReviewStatus.PENDING) {
                continue;
            }
            judged++;
            if (r.getStatus() == FraudReviewStatus.APPROVED) {
                approved++;
            }
        }
        return new long[]{judged, approved};
    }

    /**
     * 같은 심사인가.
     *
     * <p><b>id 로만 보면 저장 전 객체에서 터진다.</b> {@code flagged} 로 막 만든 항목은 id 가
     * {@code null} 이라 {@code equals} 가 널 참조가 된다. 영속 컨텍스트 안에서는 같은 인스턴스가
     * 돌아오는 것이 보통이므로 참조를 먼저 보고, 둘 다 id 가 있을 때만 id 로 본다.
     */
    private static boolean isSame(FraudReview a, FraudReview b) {
        if (a == b) {
            return true;
        }
        return a.getId() != null && a.getId().equals(b.getId());
    }

    /**
     * 근거 문자열을 규칙 이름과 괄호 안 값으로 가른다.
     *
     * <p>{@link RuleFalsePositiveService} 의 {@code rulesOf} 와 <b>같은 규칙으로 쪼갠다.</b>
     * 다르게 쪼개면 여기서 붙인 이름이 저기 통계에 없어 비율이 늘 비게 된다. 다만 저쪽은
     * 집계라 괄호 안을 버리고, 여기는 화면에 보여줄 값이라 남긴다.
     */
    private Set<Token> tokensOf(String reasons) {
        Set<Token> out = new LinkedHashSet<>();
        if (reasons == null || reasons.isBlank()) {
            return out;
        }
        for (String token : reasons.split(",")) {
            String t = token.trim();
            int paren = t.indexOf('(');
            if (paren < 0) {
                if (!t.isEmpty()) {
                    out.add(new Token(t, null));
                }
                continue;
            }
            String name = t.substring(0, paren).trim();
            int close = t.lastIndexOf(')');
            String detail = close > paren ? t.substring(paren + 1, close).trim() : null;
            if (!name.isEmpty()) {
                out.add(new Token(name, detail == null || detail.isEmpty() ? null : detail));
            }
        }
        return out;
    }

    private record Token(String name, String detail) {}

    /** {@code FraudReviewView} 와 같은 방식으로 가린다. 원본 카드 키는 모듈 밖으로 안 나간다. */
    private static String mask(String cardKey) {
        if (cardKey == null) {
            return null;
        }
        if (cardKey.length() <= 8) {
            return "****";
        }
        return cardKey.substring(0, 4) + "****" + cardKey.substring(cardKey.length() - 4);
    }
}
