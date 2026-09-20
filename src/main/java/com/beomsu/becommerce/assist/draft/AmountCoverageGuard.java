package com.beomsu.becommerce.assist.draft;

import org.springframework.stereotype.Component;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 서술이 <b>사실 묶음의 금액을 빠뜨렸는지</b> 본다. {@link NumericProvenanceGuard} 와 방향이 반대다.
 *
 * <p>출처 검증은 <b>없는 숫자를 지어냈는지</b>를 본다. 이 가드는 <b>있는 숫자를 버렸는지</b>를 본다.
 * 둘 다 통과해야 서술이 나간다.
 *
 * <p><b>왜 필요한지는 재고 알았다.</b> 서술 30건을 기계로 세니 템플릿은 금액 결손이 0건인데
 * 모델은 4건이었다. 그리고 빠뜨린 자리가 하필 <b>대사 결과가 "외부에만 있음"</b>인 건들이었다 —
 * 사실이 네 개뿐이라 짧은데, 짧을수록 모델이 요약하려다 숫자를 버린다.
 *
 * <p>그 유형은 잔여 원인 작업에서 <b>가장 위험한 유형</b>으로 짚은 바로 그것이다. 결제사는
 * 처리했다는데 우리 장부엔 없는 건이고, 운영자가 제일 먼저 봐야 할 것이 그 금액이다.
 * <b>금액 없는 요약은 그 자리에서 쓸모가 없다.</b>
 *
 * <p><b>선호가 아니라 결손이라 기계로 센다.</b> "어느 쪽 문장이 나은가"는 판정하는 쪽의 편향이
 * 들어가서 심판 모델은 순서에 흔들렸고, 강한 심판을 세워도 템플릿이 한눈에 구별돼 블라인드가
 * 성립하지 않았다. 금액이 들어 있는지는 그 문제가 없다.
 */
@Component
public class AmountCoverageGuard {

    /**
     * 서술에서 빠진 금액을 돌려준다. 비어 있으면 다 담긴 것이다.
     *
     * <p>자리표(1,234)와 맨숫자(1234) 둘 다 인정한다. 표기 방식은 결손이 아니다.
     */
    public List<Long> missing(String text, FactPack facts) {
        if (text == null || text.isBlank()) {
            return List.copyOf(facts.amounts());
        }
        return facts.amounts().stream()
                .filter(a -> !mentions(text, a))
                .sorted()
                .toList();
    }

    private boolean mentions(String text, long amount) {
        return standalone(text, NumberFormat.getNumberInstance(Locale.KOREA).format(amount))
                || standalone(text, Long.toString(amount));
    }

    /**
     * 그 숫자가 <b>그 값으로</b> 나오는지 본다. 부분 문자열로 찾으면 가드가 열리는 쪽으로 틀린다 —
     * {@code contains("17300")} 은 본문에 <b>173000</b> 이 있어도 참이고, {@code "17,300"} 은
     * <b>117,300</b> 안에서도 잡힌다. 엉뚱한 금액이 결손을 덮어 준다.
     *
     * <p>앞은 숫자·쉼표가 아니어야 하고(<b>1</b>17,300 배제), 뒤는 숫자거나 쉼표+숫자가 아니어야
     * 한다(17300<b>0</b> 과 17,300<b>,000</b> 배제). {@code "외부 17,300, 내부 기록 없음"} 처럼
     * 쉼표 뒤에 숫자가 안 오는 자리는 통과시킨다 — 자릿수 구분이 아니라 문장부호다.
     */
    private boolean standalone(String text, String needle) {
        return Pattern.compile("(?<![\\d,])" + Pattern.quote(needle) + "(?!\\d|,\\d)")
                .matcher(text).find();
    }
}
