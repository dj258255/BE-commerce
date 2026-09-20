package com.beomsu.becommerce.reconciliation.cause;

import com.beomsu.becommerce.reconciliation.ResolveCause;

/**
 * <b>사람이 반복해서 같은 답을 낸 자리</b>. 규칙으로 올릴 후보다.
 *
 * <p>대사가 안 맞는 건은 사람이 원인을 골라 확정한다. 그런데 같은 모양의 건에 사람이 계속 같은
 * 원인을 고르고 있다면, 그건 이미 규칙이다. 사람 손을 거칠 이유가 없다.
 *
 * <p><b>모델을 붙이지 않는다.</b> 이 자리에 모델을 붙여 봤다가 껐다. 가드를 다 채우고 홀드아웃
 * 45건으로 재 보니 규칙 대비 개선이 0이었다. 사람이 이미 규칙처럼 고르고 있는 자리라
 * 확률로 답할 이유가 없었다. 대신 <b>그 규칙성을 세어 보여 주는 쪽</b>으로 방향을 바꿨다.
 *
 * @param type        대사 결과 유형(AMOUNT_MISMATCH 등)
 * @param cause       사람이 고른 원인
 * @param resolved    이 (유형, 원인) 짝으로 확정된 건수
 * @param typeTotal   같은 유형에서 확정된 전체 건수
 * @param share       {@code resolved / typeTotal}. 1.0 이면 그 유형은 늘 이 원인이었다
 */
public record RulePromotionCandidate(String type, ResolveCause cause,
                                     long resolved, long typeTotal, double share) {

    /**
     * 규칙으로 올릴 만한가.
     *
     * <p>두 조건을 <b>같이</b> 본다. 비율만 보면 2건 중 2건이 100%로 잡히고, 건수만 보면 반반
     * 갈리는 유형도 올라온다. <b>돈이 걸린 판정이라 둘 다 넘겨야 후보로 친다.</b>
     */
    public boolean promotable() {
        return resolved >= MIN_RESOLVED && share >= MIN_SHARE;
    }

    /** 이 밑으로는 표본이 얇아 규칙이라고 부를 수 없다. */
    public static final long MIN_RESOLVED = 10;

    /**
     * 이 밑으로는 사람이 갈라서 판단하고 있다는 뜻이다.
     *
     * <p>0.9 로 잡은 것은 <b>열 건에 한 건꼴로 다른 답이 나오면 자동으로 넘기면 안 되기</b>
     * 때문이다. 대사는 틀리면 장부에 남는다.
     */
    public static final double MIN_SHARE = 0.9;
}
