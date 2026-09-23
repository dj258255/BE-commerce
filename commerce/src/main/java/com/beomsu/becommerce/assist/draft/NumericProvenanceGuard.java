package com.beomsu.becommerce.assist.draft;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 초안에 나온 <b>금액과 날짜가 출처에 실제로 있는지</b> 대조한다. 없으면 초안을 버린다.
 *
 * <p><b>이 장치가 보장하는 것은 출처뿐이다.</b> 초안에 나온 숫자가 코드가 낸 값인지만 본다.
 * 그 숫자가 <b>맞는 주장에 쓰였는지</b>는 보지 못한다 — 외부 기록에 100,000원이 있으면
 * "청구 금액은 그대로 유지됩니다"도 숫자 검증은 통과한다. 그 층은 루브릭과 심판이 맡는다.
 * 이름을 {@code NumberGuard} 에서 바꾼 이유가 이거다. 앞 이름은 "숫자를 지킨다"로 읽혀
 * 실제 보장보다 넓었다.
 *
 * <p><b>왜 필요한가</b>: 모델이 지어낸 숫자는 근거 있는 숫자와 문장에서 구별되지 않는다.
 * "차액 3,000원"과 "차액 3,200원"은 읽는 사람에게 똑같이 그럴듯하다. 그래서 사람 검토에
 * 맡길 수 없다 — 검토자도 그 숫자가 어디서 왔는지 모르기 때문이다.
 * 프로덕션에서 쓰이는 방식이 이것이다: 출처 시스템에서 찾을 수 없는 값을 참조하면
 * 그 출력을 거부한다.
 *
 * <p><b>이 검사는 템플릿 구현에도 똑같이 건다.</b> 지금은 템플릿이 사실만 옮기니 통과가
 * 당연하지만, 그래서 더 걸어야 한다. 모델 어댑터가 붙는 날 검사를 <b>새로 만드는 게 아니라
 * 이미 돌고 있어야</b> 하고, 그때까지 이 검사 자체가 회귀 테스트로 검증돼 있어야 한다.
 *
 * <h2>무엇을 검사하고 무엇을 넘기나</h2>
 * <ul>
 *   <li><b>검사</b> — {@code 원}이 붙은 금액, {@code yyyy-MM-dd} 및 {@code yyyy년 M월 d일} 날짜.
 *       사실을 주장하는 숫자들이다</li>
 *   <li><b>통과</b> — 그 밖의 맨 숫자({@code 2건}, {@code 3일 이내}, {@code 1차}).
 *       세는 말이나 안내 문구라 출처 대조 대상이 아니다</li>
 * </ul>
 * 이 경계를 넓히면(맨 숫자까지 검사) 정상 초안이 대량으로 반려되고, 좁히면
 * 지어낸 금액이 {@code 원} 없이 새어 나간다. <b>금액과 날짜만</b> 잡는 것이
 * 지금 초안이 실제로 주장하는 사실의 범위와 일치한다.
 */
@Component
public class NumericProvenanceGuard {

    /** 1,000원 / 10000 원 — 천 단위 구분자 유무 모두. */
    private static final Pattern AMOUNT = Pattern.compile("([0-9][0-9,]*)\\s*원");
    /** 뒤에 시각이 붙어도 잡는다 — {@code 2026-09-06T11:06} 의 T 는 단어 경계가 아니다. */
    private static final Pattern ISO_DATE = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)");
    private static final Pattern KO_DATE =
            Pattern.compile("(\\d{4})년\\s*(\\d{1,2})월\\s*(\\d{1,2})일");

    /**
     * 검증한다.
     *
     * @return 출처에서 확인되지 않은 값들. <b>비어 있으면 통과</b>
     */
    public List<String> verify(String draft, FactPack facts) {
        if (draft == null || draft.isBlank()) {
            return List.of();
        }
        List<String> bad = new ArrayList<>();

        Matcher m = AMOUNT.matcher(draft);
        while (m.find()) {
            String raw = m.group(1);
            long value;
            try {
                value = Long.parseLong(raw.replace(",", ""));
            } catch (NumberFormatException e) {
                bad.add("금액 해석 불가: " + raw + "원");     // 파싱 실패도 통과시키지 않는다
                continue;
            }
            if (!facts.amounts().contains(value)) {
                bad.add("출처에 없는 금액: " + raw + "원");
            }
        }

        Matcher iso = ISO_DATE.matcher(draft);
        while (iso.find()) {
            checkDate(iso.group(1), iso.group(2), iso.group(3), iso.group(), facts, bad);
        }
        Matcher ko = KO_DATE.matcher(draft);
        while (ko.find()) {
            checkDate(ko.group(1), ko.group(2), ko.group(3), ko.group(), facts, bad);
        }
        return List.copyOf(bad);
    }

    private void checkDate(String y, String mo, String d, String shown,
                           FactPack facts, List<String> bad) {
        try {
            LocalDate date = LocalDate.of(
                    Integer.parseInt(y), Integer.parseInt(mo), Integer.parseInt(d));
            if (!facts.dates().contains(date)) {
                bad.add("출처에 없는 날짜: " + shown);
            }
        } catch (java.time.DateTimeException | NumberFormatException e) {
            bad.add("날짜 해석 불가: " + shown);
        }
    }
}
