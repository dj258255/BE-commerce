package com.beomsu.becommerce.assist.draft;

import java.util.Optional;

/**
 * 초안 생성기. <b>구현체가 템플릿이든 모델이든 이 인터페이스 뒤에 숨는다.</b>
 *
 * <p>포트를 먼저 만드는 이유는 두 가지다.
 *
 * <p><b>하나, 규제.</b> 2026-04-20 시행세칙 개정으로 금융회사 내부망 SaaS는 열렸지만
 * 생성형 AI는 아직 망분리 예외가 아니다. 지금 외부 API를 부르는 코드를 도메인에 박아두면
 * 규제가 열릴 때가 아니라 <b>지금</b> 문제가 된다.
 *
 * <p><b>둘, 평가 순서.</b> 모델을 고르기 전에 "무엇이 좋은 초안인가"를 먼저 재야 한다.
 * Klarna가 되돌린 이유가 기술이 아니라 지표 설계 순서였다. 포트가 있으면
 * {@code DraftEvalHarnessTest}가 어떤 구현이 오든 같은 잣대로 잰다.
 *
 * <p>구현이 늘어나도 {@link DraftService}는 바뀌지 않는다. 스프링이 여러 빈 중
 * {@code app.assist.draft-provider} 설정에 맞는 것을 고른다.
 */
public interface DraftPort {

    /**
     * 사실 묶음으로 상담원용 초안을 만든다.
     *
     * @param facts 쓸 수 있는 사실의 전부. <b>여기 없는 숫자를 만들면 안 된다</b>
     * @return 초안. 만들 수 없으면 {@link Optional#empty()} —
     *         <b>빈 값을 돌려주는 게 지어낸 문장보다 낫다</b>
     */
    Optional<String> draft(FactPack facts);

    /**
     * 지적받은 것만 고쳐 다시 쓴다. <b>기본은 "못 한다"</b> — 템플릿은 고칠 게 없다.
     *
     * <p><b>왜 별도 메서드인가</b>: 한 번에 다 시키려고 지시를 늘렸다가 세 번 연속 나빠졌다.
     * 긴 프롬프트에서는 지시들이 서로 경쟁하고, 모델이 어느 제약을 지킬지
     * <b>예측 불가하게</b> 고른다. 짧은 프롬프트 하나에 목표 하나면 그 경쟁이 없다.
     *
     * <p><b>비평은 모델이 하지 않는다.</b> 흔한 self-refine 은 모델이 자기 글을 비평하게 하는데,
     * 그러면 비평 자체가 틀릴 수 있다. 여기서는 {@link DraftRubric} 이 <b>결정적으로</b>
     * 무엇이 빠졌는지 짚고, 모델은 그것만 고친다.
     *
     * @param issues 루브릭이 짚은 미충족 항목. <b>비어 있으면 부르지 않는다</b>
     * @return 고친 초안. 못 고치면 {@link Optional#empty()} — 원본을 쓴다
     */
    default Optional<String> revise(FactPack facts, String draft, java.util.List<String> issues) {
        return Optional.empty();
    }

    /** 어느 구현이 만들었는지. 초안에 함께 실어 사람이 출처를 알게 한다. */
    String name();
}
