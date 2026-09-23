package com.beomsu.becommerce.assist.draft;

import java.util.Optional;

/**
 * 초안이 <b>근거 없이 단정하는지</b>를 다른 모델에게 묻는다 (ADR-014).
 *
 * <p><b>왜 필요한가</b>: 숫자 검증과 루브릭을 <b>둘 다 통과</b>하는데 주장이 틀린 초안이
 * 실측에서 나왔다(13 문서 실험 8).
 * <blockquote>
 * 저희 내부 기록에 해당 주문이 없습니다 … <b>청구 금액은 100,000원으로 그대로 유지됩니다.</b>
 * </blockquote>
 * 100,000원은 외부 기록에 있으니 {@link NumericProvenanceGuard} 를 통과하고, 영향 표현이 있으니
 * {@link DraftRubric} 도 통과한다. <b>기록조차 없는 건에 청구가 멀쩡하다고 단언</b>하는데
 * 코드로는 잡을 수 없다 — 숫자가 아니라 <b>주장</b>의 문제다.
 *
 * <p><b>왜 다른 계열인가</b>: 모델은 자기 계열 출력을 후하게 평가한다(self-enhancement).
 * 초안을 Qwen 이 썼으므로 심판은 Qwen 이면 안 된다. Llama·Mistral 계열은 이 편향을
 * 보이지 않는다는 관측이 있어 Llama 를 쓴다.
 *
 * <p><b>심판을 믿지 않는다.</b> 프런티어 모델도 까다로운 편향 벤치마크에서 오류율이
 * 50%를 넘는다는 보고가 있다. 그래서 이 판정은 <b>초안을 버리지 않고 표시만</b> 한다 —
 * 지어낸 숫자(버림)와 내부 용어(표시) 사이의 세 번째 등급이다.
 * 그리고 <b>심판 자체를 검증했다</b> — 아는 나쁜 건·좋은 건·중립 건 3종으로 확인하지 않으면
 * 심판이 무엇을 하는지 모른 채 숫자만 늘어난다.
 */
public interface DraftJudge {

    /**
     * 판정.
     *
     * @param grounded 근거 있는가. <b>false 면 사실에 없는 것을 단정했다</b>
     * @param quote    문제된 문장. 없으면 빈 문자열
     * @param why      한 문장 이유. <b>사람이 읽고 판단할 근거다</b> — 판정만 있으면 못 쓴다
     * @param judge    누가 판정했나. 초안 생성 모델과 <b>달라야</b> 의미가 있다
     */
    record Verdict(boolean grounded, String quote, String why, String judge) {

        /** 심판이 없거나 실패했을 때. <b>통과로 본다</b> — 심판 때문에 업무가 막히면 안 된다. */
        static Verdict unavailable(String reason) {
            return new Verdict(true, "", reason, "none");
        }
    }

    /**
     * 초안을 검수한다.
     *
     * @return 판정. 부를 수 없으면 {@link Optional#empty()} — 호출자가 통과로 처리한다
     */
    Optional<Verdict> judge(FactPack facts, String draft);
}
