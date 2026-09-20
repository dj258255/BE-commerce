package com.beomsu.becommerce.assist.draft;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 초안을 <b>정답 없이</b> 채점한다 (reference-free).
 *
 * <p><b>왜 정답을 안 쓰나</b>: 실험 6에서 기준을 잘못 잡아 정반대 결론을 낼 뻔했다.
 * "발송본"이 특정 모델의 초안을 고쳐 만든 것이라 <b>그 모델에 유리했다.</b>
 * 답이 열려 있는 일에서는 하나의 정답이 유효한 변형을 전부 틀린 것으로 만든다.
 * 그래서 업계는 이런 일에 <b>정답 대신 규칙</b>으로 채점한다.
 *
 * <p><b>여기서 하는 것은 코드 기반 단언이다.</b> 필수 요소가 있는지, 금지된 것이 없는지를
 * 결정적으로 본다. 빠르고, 공짜고, 매번 같은 값이 나온다.
 * <b>어조나 설득력은 못 본다</b> — 그건 사람이나 다른 계열 모델이 볼 몫이다.
 *
 * <p><b>무엇을 보는가는 실측에서 나왔다.</b> 상상해서 만든 항목이 아니라,
 * 블라인드 리뷰에서 실제로 고쳐야 했던 것들이다(13 문서 실험 5).
 * <ul>
 *   <li>핵심 수치를 말하는가 — 초안이 "무슨 일이 있었나"만 말하고
 *       정작 문제인 차액을 빼먹는 일이 실제로 있었다</li>
 *   <li>고객에게 미치는 영향을 말하는가 — 발송본이 전부 초안보다 길어진 이유가 이것이었다</li>
 *   <li>내부 코드가 없는가 / 지어낸 숫자가 없는가 — 이미 있는 두 검사를 여기 모은다</li>
 *   <li>길이가 적당한가 — 너무 짧으면 설명이 아니고, 너무 길면 상담원이 잘라내야 한다</li>
 * </ul>
 */
@Component
public class DraftRubric {

    /**
     * <b>실질적인</b> 고객 영향 표현. 하나라도 있어야 통과.
     *
     * <p>처음엔 "안내드리겠습니다" 같은 마무리 인사도 여기 넣었다가 <b>검사가 무력해졌다.</b>
     * 실측에서 6건 전부가 그 한 마디만으로 통과했는데, 그 문장은 고객에게
     * <b>아무것도 알려주지 않는다.</b> 루브릭 평균이 4.97/5 로 나왔지만 그건
     * 초안이 좋아서가 아니라 검사가 물렀기 때문이었다.
     *
     * <p>그래서 <b>돈이 어떻게 되는지</b>나 <b>고객이 무엇을 하면 되는지</b>를 말하는 표현만 남겼다.
     *
     * <p><b>그러다 이번엔 너무 빡빡해졌다.</b> 상투어를 빼면서 {@code 그대로} 까지 같이 뺐는데,
     * 모델이 "청구 금액은 <b>그대로 유지</b>됩니다"라고 제대로 쓴 것을 못 알아봤다.
     * 그 오탐 하나로 <b>"2단계 수정은 소용없다"는 틀린 결론을 낼 뻔했다.</b>
     *
     * <p><b>키워드 목록은 양쪽으로 부서진다</b> — 넓히면 상투어가 통과하고 좁히면 정답을 놓친다.
     * 여기까지가 코드로 할 수 있는 한계이고, 어조나 함의는 다른 계열 모델이나 사람이 볼 몫이다.
     */
    private static final List<String> IMPACT_PHRASES = List.of(
            "변동이 없", "변동은 없", "변동 없",           // 청구 금액 유지
            "추가 청구", "추가로 청구", "청구되지",         // 추가 부담 여부
            "보내주시", "보내 주시", "제출해",              // 고객이 할 일
            "환불", "처리해 드리", "처리해드리",            // 조치
            "정상적으로 처리", "문제가 없", "문제는 없",    // 결제 자체는 무사
            "그대로 유지", "그대로입니다", "그대로 청구",     // 청구 유지(다른 표현)
            "영향은 없", "영향이 없", "영향을 주지 않");     // 영향 없음(다른 표현)

    /**
     * 마무리 인사. <b>이것만으로는 통과시키지 않는다.</b>
     * 어느 초안에나 붙는 말이라 있으나 없으나 정보량이 같다.
     */
    private static final List<String> CLOSING_ONLY = List.of(
            "안내드리", "안내해 드리", "연락드리", "알려드리");

    /** 자릿수 구분된 금액 — 근거 문장의 핵심 수치를 뽑는 데 쓴다. */
    private static final Pattern AMOUNT = Pattern.compile("([0-9][0-9,]*)\\s*원");

    /**
     * <b>근거 없이 "돈은 멀쩡하다"고 단정하는</b> 표현들.
     *
     * <p>원인이 확정된 건에는 맞는 말이고, 모르는 건에는 <b>확인되지 않은 주장</b>이다.
     * 다른 계열 심판이 처음 잡아준 결함인데, 심판은 흔들린다 —
     * 초안이 고쳐진 뒤에도 같은 건을 계속 위반이라고 했다(오탐 70%).
     * 그래서 <b>아는 결함은 코드로 고정</b>한다. 심판은 모르는 결함을 찾는 데 쓴다.
     */
    private static final List<String> UNSUPPORTED_CLAIM = List.of(
            "그대로 유지", "변동이 없", "변동은 없", "변동 없",
            "영향은 없", "영향이 없", "영향을 주지 않");

    /**
     * <b>불확실할 때의 약속</b> — 원인을 몰라도 "추가로 청구하지 않겠다"는 말할 수 있다.
     *
     * <p>이건 <b>주장이 아니라 약속</b>이다. "이미 청구된 것이 멀쩡하다"는 확인되지 않은
     * 주장이지만, "앞으로 더 청구하지 않겠다"는 우리가 지킬 수 있는 것이다.
     * 고객이 원인 불명 상황에서 가장 궁금해하는 것이 그거다.
     */
    private static final List<String> UNCERTAIN_PROMISE = List.of(
            "추가로 청구되는 금액은 없", "추가 청구는 없", "추가로 청구되지",
            "추가 비용은 없", "추가로 결제되지");

    /** 이 문의와 상관없는 내부 일정. 있으면 상담원이 지워야 한다. */
    private static final List<String> IRRELEVANT_INTERNAL = List.of(
            "정산 확정일", "정산 항목", "에스크로", "포인트 적립", "원장 분개", "자동해제");

    private static final int MIN_LEN = 40;
    private static final int MAX_LEN = 400;

    private final NumericProvenanceGuard numberGuard;
    private final CustomerGlossary glossary;

    DraftRubric(NumericProvenanceGuard numberGuard, CustomerGlossary glossary) {
        this.numberGuard = numberGuard;
        this.glossary = glossary;
    }

    /**
     * 채점 결과.
     *
     * @param passed 통과한 항목 수
     * @param total  전체 항목 수
     * @param failed 못 지킨 항목들. <b>이름만이 아니라 왜인지 적는다</b> —
     *               점수만 있으면 무엇을 고쳐야 할지 모른다
     * @param bonus  <b>필수는 아니지만 좋은 것</b>들. 통과 여부를 바꾸지 않는다.
     *
     *               <p>왜 필수가 아닌가: 필수로 걸면 모델이 점수를 채우려고
     *               <b>알 수 없는 것까지 단언한다</b>(실험 9에서 실제로 그랬다).
     *               그래서 <b>재기만 하고 요구하지 않는다</b> — 프롬프트에도 넣지 않는다.
     *
     *               <p>왜 필요한가: 루브릭 6항목만으로는 프런티어 모델과 8B 가 <b>둘 다 만점</b>이라
     *               구분이 안 됐다(실험 11). 잣대가 구분하지 못하면 개선을 이끌 수도 없다.
     */
    public record Score(int passed, int total, List<String> failed, List<String> bonus) {

        /** 필수 항목만 본 비율. 가점은 여기 안 들어간다 — 통과 기준을 흔들지 않는다. */
        public double ratio() {
            return total == 0 ? 0 : (double) passed / total;
        }

        /** 비교용 점수. 같은 만점끼리 <b>가르는</b> 값이다. */
        public int comparable() {
            return passed + bonus.size();
        }
    }

    /** 초안 하나를 채점한다. {@code draft} 가 없으면 전부 실패로 본다. */
    public Score score(String draft, FactPack facts) {
        List<String> failed = new ArrayList<>();
        int total = 6;

        if (draft == null || draft.isBlank()) {
            return new Score(0, total, List.of("초안 없음"), List.of());
        }

        // ① 지어낸 숫자가 없는가 — 가장 무거운 항목. 틀리면 초안을 못 쓴다
        List<String> bad = numberGuard.verify(draft, facts);
        if (!bad.isEmpty()) {
            failed.add("지어낸 값: " + bad);
        }

        // ② 내부 코드가 없는가
        List<String> jargon = glossary.findJargon(draft);
        if (!jargon.isEmpty()) {
            failed.add("내부 용어: " + jargon);
        }

        // ③ 핵심 수치를 말하는가 — 분류기 근거의 첫 금액이 그 건의 쟁점이다
        String key = keyFigure(facts.causeHint());
        if (key != null && !mentionsAmount(draft, key)) {
            failed.add("핵심 수치 누락: " + key + "원 (이 건의 쟁점)");
        }

        // ④ 고객에게 미치는 영향을 말하는가 — <원인을 아는 건에만> 요구한다.
        //
        // 처음엔 모든 건에 요구했다. 그랬더니 2단계 수정이 점수를 채우려고
        // 원인 불명 건에도 "청구 금액은 그대로 유지됩니다"라고 <단언>하게 만들었다.
        // 다른 계열 심판이 그걸 잡았다 — "수수료로도 취소로도 설명되지 않는데
        // 청구 유지라는 근거가 사실에 없다".
        //
        // 지표를 올리려다 지표가 목표가 된 것이다. 원인을 모르면 결과도 장담할 수 없고,
        // 그때 요구해야 할 것은 영향이 아니라 <모른다는 사실>이다.
        if (requiresImpact(facts.causeHint()) && IMPACT_PHRASES.stream().noneMatch(draft::contains)) {
            boolean onlyClosing = CLOSING_ONLY.stream().anyMatch(draft::contains);
            failed.add(onlyClosing
                    ? "고객 영향 없음 — 마무리 인사뿐이다. 돈이 어떻게 되는지를 말하지 않았다"
                    : "고객 영향 없음 — <그래서 나는 어떻게 되나>가 빠졌다");
        }

        // ⑤ 원인을 모르는데 <돈은 멀쩡하다>고 단정하지 않는가
        //
        // "추가로 청구되지 않겠다"는 우리가 지킬 <약속>이라 괜찮지만,
        // "이미 청구된 것이 멀쩡하다"는 확인되지 않은 <주장>이다. 그 둘은 다르다.
        if (!requiresImpact(facts.causeHint())) {
            UNSUPPORTED_CLAIM.stream().filter(draft::contains).findFirst().ifPresent(
                    c -> failed.add("근거 없는 단정: \"" + c + "\" — 원인을 모르는데 결과를 장담했다"));
        }

        // ⑥ 길이
        int len = draft.replaceAll("\\s+", "").length();
        if (len < MIN_LEN) {
            failed.add("너무 짧음 (" + len + "자)");
        } else if (len > MAX_LEN) {
            failed.add("너무 긺 (" + len + "자) — 상담원이 잘라내야 한다");
        }

        // 가점 — 통과를 바꾸지 않고 <같은 만점끼리 가르는> 축이다.
        List<String> bonus = new ArrayList<>();
        if (!requiresImpact(facts.causeHint())
                && UNCERTAIN_PROMISE.stream().anyMatch(draft::contains)) {
            bonus.add("불확실할 때 추가 청구 없음을 약속했다");
        }
        if (IRRELEVANT_INTERNAL.stream().noneMatch(draft::contains)) {
            bonus.add("상관없는 내부 일정을 넣지 않았다");
        }

        return new Score(total - failed.size(), total, List.copyOf(failed), List.copyOf(bonus));
    }


    /**
     * 고객 영향을 요구해도 되는가 — <b>원인이 확정된 건만</b>.
     *
     * <p>{@code DECISIVE} 는 산수로 확정된 것이라 결과도 말할 수 있다(수수료 차감이면
     * 청구는 그대로다). {@code LIKELY}·{@code WEAK} 이거나 원인이 없으면 <b>결과를 모른다.</b>
     * 그때 영향을 요구하면 모델은 지어낸다.
     */
    private static boolean requiresImpact(String causeHint) {
        return causeHint != null && causeHint.contains("DECISIVE");
    }

    /**
     * 이 건의 쟁점이 되는 금액. 분류기 근거의 <b>첫</b> 금액을 쓴다 —
     * 규칙은 결정적인 수치를 앞세워 쓰기 때문이다("차액 8,888원이 …").
     *
     * <p>근거가 없거나 금액이 없으면 null. 그 경우 이 항목은 채점하지 않는다 —
     * 없는 것을 말하라고 요구할 수 없다.
     */
    private static String keyFigure(String causeHint) {
        if (causeHint == null || causeHint.isBlank()) {
            return null;
        }
        Matcher m = AMOUNT.matcher(causeHint);
        return m.find() ? m.group(1).replace(",", "") : null;
    }

    /** 표기 차이(1,112 / 1112)를 같은 값으로 본다. */
    private static boolean mentionsAmount(String draft, String normalized) {
        Matcher m = AMOUNT.matcher(draft);
        while (m.find()) {
            if (m.group(1).replace(",", "").equals(normalized)) {
                return true;
            }
        }
        return false;
    }
}
