package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.fraud.FraudReviewFacts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 초안이 <b>사실을 넓혀서</b> 말하는지 본다. {@code NumericProvenanceGuard} 가 못 잡는 자리다.
 *
 * <p>출처 검증은 <b>없는 숫자를 지어냈는지</b>를 보고, 금액 결손은 <b>있는 숫자를 버렸는지</b>를
 * 본다. 이 가드는 <b>숫자는 맞는데 그 숫자가 뜻하지 않는 것을 덧붙였는지</b>를 본다.
 *
 * <p><b>실측에서 나온 두 모양만 잡는다.</b> 일반적인 "넓히지 마라" 는 함의 판정이라 이 자리에서
 * 못 한다. 좁게 잡고 못 잡는 것을 밝혀 두는 편이, 넓게 잡아 멀쩡한 문장을 반려하는 것보다 낫다.
 *
 * <ol>
 *   <li><b>수량 부풀리기</b> — 사실이 "1분에 3건" 인데 초안이 "3건 <b>이상</b>" 이라고 쓴다.
 *       규칙이 준 값은 정확한 수이지 하한이 아니다</li>
 *   <li><b>심사를 거래로 넓히기</b> — 없는 것은 <b>판정이 끝난 심사</b>인데 초안이
 *       "이전 <b>결제</b>가 없다" 고 쓴다. 결제 이력이 없다는 뜻이 되어 첫 거래로 읽힌다</li>
 * </ol>
 *
 * <p>둘 다 <b>심사자의 판단을 바꾼다.</b> 첫째는 규칙이 실제보다 세게 걸린 것처럼 읽히고,
 * 둘째는 이 카드가 처음 쓰인 것처럼 읽힌다.
 */
@Component
public class FactWideningGuard {

    /** 정확한 수 뒤에 붙어 하한으로 바꾸는 말. */
    private static final Pattern INFLATED =
            Pattern.compile("(\\d+)\\s*건\\s*(이상|초과|넘|以上)");

    /** 같은 카드 이력을 <b>결제·거래</b>로 넓혀 말하는 자리. */
    private static final Pattern WIDENED_HISTORY =
            Pattern.compile("(결제|거래|구매)\\s*(이력|내역|기록)?\\s*(이|가|은|는)?\\s*없");

    /**
     * 넓힌 자리를 돌려준다. 비어 있으면 통과다.
     *
     * @param text  검사할 초안
     * @param facts 이 심사의 사실. 넓혔는지는 사실과 견줘야 알 수 있다
     */
    public List<String> verify(String text, FraudReviewFacts facts) {
        if (text == null || text.isBlank() || facts == null) {
            return List.of();
        }
        List<String> bad = new ArrayList<>();

        Matcher m = INFLATED.matcher(text);
        while (m.find()) {
            // 규칙이 그 수를 정확한 값으로 줬을 때만 잡는다. 사실에 없는 수면 출처 검증이 맡는다.
            if (isExactCount(facts, m.group(1))) {
                bad.add("정확한 수를 하한으로 넓힘: " + m.group());
            }
        }

        // 판정이 끝난 심사가 <있는> 경우에는 이 문장이 아예 안 나온다. 없을 때만 본다.
        if (facts.sameCardJudged() == 0 && WIDENED_HISTORY.matcher(text).find()) {
            bad.add("없는 것은 판정이 끝난 심사인데 결제·거래가 없다고 씀");
        }
        return bad;
    }

    /** 그 수가 규칙 근거에 <b>그대로</b> 적힌 값인가. */
    private boolean isExactCount(FraudReviewFacts facts, String number) {
        for (var rule : facts.firedRules()) {
            String detail = rule.detail();
            if (detail == null) {
                continue;
            }
            for (String token : detail.split("[^0-9]+")) {
                if (token.equals(number)) {
                    return true;
                }
            }
        }
        return false;
    }
}
