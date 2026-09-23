package com.beomsu.becommerce.assist.draft;

import org.springframework.stereotype.Component;

/**
 * {@link FactPack} 을 프롬프트로 만든다. <b>어댑터가 어느 모델이든 같은 프롬프트를 쓴다.</b>
 *
 * <p>왜 분리했나: 모델을 바꿨는데 결과가 달라지면 <b>모델 때문인지 프롬프트 때문인지</b>
 * 알 수 없다. 프롬프트를 어댑터마다 두면 그 구분이 영영 안 된다.
 * 부하 테스트에서 어댑터 구현 차이가 측정에 섞이면 안 되는 것과 같은 이유다.
 *
 * <p><b>지시가 곧 방어선의 일부다.</b> 여기서 "주어진 사실 밖의 숫자를 쓰지 말라"고 못 박고,
 * {@link NumericProvenanceGuard} 가 지켜졌는지 <b>기계로</b> 확인한다. 지시만 있고 검사가 없으면
 * 모델이 지킬 때만 지켜지고, 검사만 있고 지시가 없으면 반려율만 높아진다. 둘 다 필요하다.
 * Uber Genie 가 "주어진 sub-context 안에서만 답하고 출처를 인용하라"를 명시적으로
 * 지시하는 것과 같은 자리다.
 */
@Component
public class PromptBuilder {

    private final CustomerGlossary glossary;
    private final DraftExamples examples;
    /** {@code app.assist.prompt-layout=rearranged} 일 때만 켠다. 기본은 지금 배치. */
    private final boolean rearranged;

    PromptBuilder(CustomerGlossary glossary, DraftExamples examples,
                  @org.springframework.beans.factory.annotation.Value(
                          "${app.assist.prompt-layout:current}") String layout) {
        this.glossary = glossary;
        this.examples = examples;
        this.rearranged = "rearranged".equals(layout);
    }


    /** 모델에게 주는 역할과 금지사항. 사실 목록은 여기 없다 — 아래에서 붙인다. */
    private static final String SYSTEM = """
            당신은 결제사 고객센터 상담원을 돕는 보조자입니다.
            상담원이 고객에게 보낼 답변의 **초안**을 씁니다.

            반드시 지킬 것:
            1. 아래 [확인된 사실] 에 있는 내용만 쓰십시오.
            2. **금액과 날짜는 [인용해도 되는 금액]·[인용해도 되는 날짜] 에 있는 값만** 쓰십시오.
               그 목록은 <표기 허용 목록>이지 잔액·청구액이 아닙니다. 목록 자체를 설명하지 마십시오.
               계산하지 마십시오. 합계·차액·수수료를 직접 구하지 마십시오.
               사실에 없는 숫자를 쓰면 그 초안은 폐기됩니다.
            3. 원인이 적혀 있으면 "확인되었다"가 아니라 "확인 중"으로 쓰십시오.
               원인은 담당자가 확정합니다.
            4. 사과나 보상을 약속하지 마십시오. 그건 상담원이 판단합니다.
            5. 한국어 존댓말로, 5문장 이내로 씁니다.

            6. **무엇이 문제인지 먼저 쓰십시오.** 사실을 시간순으로 나열하지 마십시오.
               고객은 "무슨 일이 있었나"가 아니라 "내 돈이 어떻게 됐나"를 묻고 있습니다.
            7. 이 문의와 상관없는 정보(에스크로 일정, 포인트 적립 등)는 넣지 마십시오.

            모르는 것은 쓰지 말고 비워 두십시오. 빈 초안이 지어낸 초안보다 낫습니다.
            원인을 모를 때도 <모르는 상태라는 것>을 쓰는 것이 답입니다. 침묵이 아닙니다.
            """;

    /**
     * 시스템 프롬프트 — 역할 + 용어 사전 + 예시.
     *
     * <p>셋을 한 덩어리로 두는 이유: 어댑터가 바뀌어도 같은 것이 나가야 한다.
     * 하나라도 어댑터 쪽에 두면 모델 간 비교가 성립하지 않는다.
     */
    public String system(FactPack facts) {
        // 예시를 <케이스에 맞는 것 하나로> 갈아 끼운다. 고정 예시 하나가
        // "청구된 금액에는 변동이 없습니다"로 끝나 있어서, 모델이 기록조차 없는 건에도
        // 그 꼴을 복사했다(13 문서 실험 9). 늘리지 않고 바꾸는 것이 요점이다 —
        // 지시를 더할 때마다 나빠졌다.
        return rearranged ? rearranged(facts) : current(facts);
    }

    /** 지금까지의 배치. 글로서리가 가운데 온다. */
    String current(FactPack facts) {
        return SYSTEM + "\n" + glossary.asPromptSection() + "\n" + examples.forCase(facts);
    }

    /**
     * <b>재배치 — 지시를 앞으로, 사전을 뒤로, 핵심을 끝에서 반복.</b>
     *
     * <p>지시를 더하지 않는다. 순서만 바꾼다. 실험 6에서 지시를 두 줄 더했을 때
     * 용어 누출이 0% → 25% 로 깨졌다. 늘리는 것은 이미 해 봤고 나빠졌다.
     *
     * <p>이것이 낫다는 <b>가설</b>이지 처방이 아니다. 같은 모델·같은 설정에서
     * 두 배치를 직접 비교해야 안다(13 문서 실험 13).
     */
    String rearranged(FactPack facts) {
        return HEAD + "\n" + SYSTEM + "\n" + examples.forCase(facts) + "\n"
                + glossary.asPromptSection() + "\n" + TAIL;
    }

    /** 맨 앞 — 어기면 초안이 폐기되는 것만. */
    private static final String HEAD = """
            당신은 결제사 고객센터 상담원을 돕는 보조자입니다. 아래 셋을 어기면 초안은 폐기됩니다.

            (가) [확인된 사실] 밖의 내용을 쓰지 마십시오.
            (나) 금액과 날짜는 [인용해도 되는 금액]·[인용해도 되는 날짜] 의 값만 쓰십시오. 계산하지 마십시오.
            (다) 대문자 영문 코드를 그대로 쓰지 마십시오. 아래 [용어 바꿔 쓰기] 대로 우리말로 풉니다.
            """;

    /** 맨 끝 — 가장 신선한 자리에서 (가)(나)(다)를 다시 세운다. */
    private static final String TAIL = """
            다시 확인하십시오. 사실 밖의 내용 없음, 목록 밖의 숫자 없음, 대문자 영문 코드 없음.
            한국어 존댓말 5문장 이내로, 무엇이 문제인지부터 씁니다.
            """;

    /**
     * 수정 프롬프트 — <b>짧게, 목표 하나만.</b>
     *
     * <p>생성 프롬프트(규칙 8개 + 사전 22줄 + 예시 3개)를 여기 다시 싣지 않는다.
     * 그러면 같은 주의 예산 문제가 되풀이된다. 고칠 것 하나와 쓸 수 있는 값만 준다.
     */
    public String reviseSystem() {
        // "고치십시오"라고 썼더니 모델이 초안을 <그대로> 돌려줬다. 지적은 "빠졌다"인데
        // 지시는 "편집하라"였고, 모델은 고치기를 <기존 문장 수정>으로 읽었다.
        // 없는 것을 채우려면 <덧붙이라>고 해야 한다. 이 한 단어가 결과를 갈랐다.
        return """
                당신은 결제사 상담 초안을 보완합니다.
                지적된 내용이 <빠져 있다>는 뜻이면, 그 내용을 담은 문장을 <새로 덧붙이십시오>.

                1. 기존 문장은 그대로 두고 뒤에 문장을 추가하십시오.
                2. 금액과 날짜는 [인용해도 되는 값] 목록의 것만 쓰십시오.
                3. 대문자 영문 코드는 쓰지 마십시오.
                4. 완성된 초안 <본문만> 출력하십시오. 설명이나 머리말을 붙이지 마십시오.
                """;
    }

    /** 무엇이 빠졌는지와 쓸 수 있는 값만. 사실 목록은 초안에 이미 반영돼 있다. */
    public String reviseUser(FactPack facts, String draft, java.util.List<String> issues) {
        StringBuilder sb = new StringBuilder();
        sb.append("[초안]\n").append(draft).append("\n\n[빠진 것]\n");
        for (String i : issues) {
            sb.append("- ").append(i).append('\n');
            // 무엇을 쓰면 되는지까지 알려준다. "영향이 없다"만으로는 무엇을 덧붙일지 모른다.
            if (i.contains("고객 영향")) {
                sb.append("  청구 금액이 그대로인지, 추가 청구가 있는지, ")
                  .append("고객이 무엇을 해야 하는지 중 해당하는 것을 한 문장으로 덧붙이십시오.\n");
            }
        }
        if (!facts.amounts().isEmpty()) {
            sb.append("\n[인용해도 되는 금액] 이 목록의 값만 쓰십시오.\n");
            facts.amounts().stream().sorted()
                    .forEach(a -> sb.append("- ").append(String.format("%,d", a)).append("원\n"));
        }
        if (!facts.dates().isEmpty()) {
            sb.append("\n[인용해도 되는 날짜]\n");
            facts.dates().stream().sorted().forEach(d -> sb.append("- ").append(d).append('\n'));
        }
        sb.append("\n문장을 덧붙인 초안 본문을 출력하십시오.");
        return sb.toString();
    }

    /**
     * 사실 묶음을 사용자 프롬프트로. <b>여기서 계산하지 않는다</b> —
     * 넘기는 것은 이미 코드가 낸 값들뿐이다.
     */
    public String user(FactPack facts) {
        StringBuilder sb = new StringBuilder();
        sb.append("[주문번호]\n").append(facts.orderNo()).append("\n\n");

        sb.append("[확인된 사실]\n");
        for (String f : facts.facts()) {
            // 분쟁 사유처럼 남이 쓴 값이 섞여 있다. 줄바꿈으로 목록 구조를 깨지 못하게 눌러 담는다.
            sb.append("- ").append(UntrustedText.flatten(f)).append('\n');
        }

        if (!facts.amounts().isEmpty()) {
            // 레이블이 중요하다. 처음엔 "[쓸 수 있는 금액]"이라고 썼다가
            // 모델이 그걸 <잔액>으로 읽고 "사용 가능한 금액은 10,000원과 270원입니다"라고 썼다.
            // 목록의 성격(인용 허용 목록)을 이름에 박아야 한다.
            sb.append("\n[인용해도 되는 금액] — 사실이 아니라 <표기 허용 목록>입니다.\n")
              .append("이 목록의 값 외의 금액은 쓰지 마십시오. 잔액이나 청구액이 아닙니다.\n");
            facts.amounts().stream().sorted()
                    .forEach(a -> sb.append("- ").append(String.format("%,d", a)).append("원\n"));
        }
        if (!facts.dates().isEmpty()) {
            sb.append("\n[인용해도 되는 날짜] — 표기 허용 목록입니다.\n")
              .append("이 목록의 값 외의 날짜는 쓰지 마십시오.\n");
            facts.dates().stream().sorted()
                    .forEach(d -> sb.append("- ").append(d).append('\n'));
        }
        if (facts.causeHint() != null && !facts.causeHint().isBlank()) {
            sb.append("\n[규칙이 추정한 원인] (확정 아님 — 확인 중으로 쓰십시오)\n")
              .append(facts.causeHint()).append('\n');
        }
        if (!facts.complete()) {
            sb.append("\n[주의] 일부 시스템 기록을 가져오지 못했습니다. ")
              .append("이 사실을 초안에 밝히십시오.\n");
        }
        sb.append("\n위 사실만으로 상담원용 초안을 작성하십시오.");
        return sb.toString();
    }
}
