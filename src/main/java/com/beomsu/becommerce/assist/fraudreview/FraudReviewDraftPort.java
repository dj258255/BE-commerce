package com.beomsu.becommerce.assist.fraudreview;

import com.beomsu.becommerce.fraud.FraudReviewFacts;

import java.util.Optional;

/**
 * FDS 심사 초안 생성기. <b>구현이 템플릿이든 모델이든 이 뒤에 숨는다.</b>
 *
 * <p>{@code assist.draft.DraftPort} 와 같은 이유로 포트를 먼저 둔다. 무엇이 좋은 초안인지를
 * 먼저 재고 나서 구현을 고른다. 포트가 있으면 {@code FraudReviewDraftEvalTest} 가 어떤 구현이
 * 오든 같은 잣대로 잰다.
 *
 * <p><b>여기서 판정을 내지 않는다.</b> 초안은 심사자가 읽는 글이고, 승인·거부는 사람이 누른다.
 * 포트가 {@code approve} 같은 것을 돌려주게 만들면 그 순간 초안이 판정이 된다.
 */
public interface FraudReviewDraftPort {

    /**
     * 사실 묶음으로 심사자용 초안을 만든다.
     *
     * @param facts 쓸 수 있는 사실의 전부. <b>여기 없는 숫자를 만들면 안 된다</b>
     * @return 초안. 만들 수 없으면 {@link Optional#empty()} —
     *         <b>빈 값이 지어낸 문장보다 낫다</b>
     */
    Optional<String> draft(FraudReviewFacts facts);

    /**
     * 지적을 붙여 다시 쓰게 한다. <b>고칠 게 없으면 부르지 않는다.</b>
     *
     * <p>{@code assist.draft.DraftPort.revise} 와 같은 자리다. 상담 초안에서 필수 금액이
     * 빠지던 것을 이 되묻기로 잡았고(같은 12건에서 75% 에서 0%), 심사 초안에도 같은 구멍이
     * 있었다. 열두 건을 돌려 보니 둘이 금액을 통째로 빠뜨린 채 출처 검증을 통과했다.
     *
     * @return 수정본. 못 고치면 {@link Optional#empty()} — 부르는 쪽이 원본을 유지한다
     */
    default Optional<String> revise(FraudReviewFacts facts, String original, java.util.List<String> issues) {
        return Optional.empty();
    }

    /** 어느 구현이 만들었는지. 초안에 실어 심사자가 출처를 알게 한다. */
    String name();
}
