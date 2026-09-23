package com.beomsu.becommerce.assist.residual;

import com.beomsu.becommerce.assist.draft.NumericProvenanceGuard;
import com.beomsu.becommerce.reconciliation.ResolveCause;
import com.beomsu.becommerce.reconciliation.ResolveCause;

import java.util.Set;

/**
 * 규칙 분류기가 아무 후보도 못 낸 건에 대해 모델이 낸 원인 후보.
 *
 * <p><b>제안일 뿐이다.</b> 이 값으로 {@code resolve}를 부르지 않는다. 사람이 고르는 화면에
 * 후보 하나를 더 얹는 것이 전부이고, 그마저 섀도 모드에서는 화면에 안 나간다.
 *
 * <p>{@code figures}는 {@code rationale}에 등장하는 금액·날짜의 원천이다.
 * {@link NumericProvenanceGuard}가 이 값들이 코드가 낸 것인지 대조한다. 하나라도
 * 출처가 없으면 제안을 통째로 버린다 — 상담 초안과 같은 계약이다.
 *
 * @param cause      제안하는 원인. 반드시 {@link ResolveCause} 안의 값이다
 * @param rationale  왜 그렇게 봤는지. 사람이 눈으로 검증할 수 있어야 한다
 * @param confidence 0~100. 임계 미만이면 호출자가 기권으로 처리한다
 * @param figures    rationale 이 근거로 삼은 숫자들
 */
public record ResidualSuggestion(ResolveCause cause, String rationale, int confidence,
                                 Set<Long> figures) {

    public ResidualSuggestion {
        figures = figures == null ? Set.of() : Set.copyOf(figures);
    }
}
