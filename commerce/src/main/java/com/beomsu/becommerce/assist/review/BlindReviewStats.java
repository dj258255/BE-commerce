package com.beomsu.becommerce.assist.review;

import java.util.List;

/**
 * 표본 집계. <b>표본 수를 항상 같이 낸다</b> — 4건짜리 평균을 근거로 쓰면 안 된다.
 *
 * <p>쌍 비교 값({@code paired*})은 <b>초안 둘을 다 고친 건</b>에서만 낸다. 활성화 조건
 * 1번이 "편집률 중앙값이 <b>템플릿보다</b> 낮을 것"이라, 두 중앙값이 같은 표본에서
 * 나와야 차이를 방식 차이로 읽을 수 있다.
 *
 * @param samples            3단계까지 끝난 표본 수
 * @param medianEditRatio    모델 초안 → 수정본. <b>0이면 그대로, 1이면 통째로 새로 씀</b>
 * @param medianDivergence   블라인드 답 ↔ 모델 초안. 사람과 모델이 얼마나 다르게 썼나
 * @param usedAsIs           전혀 안 고친 건수 (편집률 0.05 미만)
 * @param rewritten          절반 이상 새로 쓴 건수 (편집률 0.5 이상)
 * @param pairedSamples      초안 둘을 다 고친 표본 수
 * @param pairedModelMedian  그 표본에서 모델 초안의 편집률 중앙값. 표본이 없으면 null
 * @param pairedBaselineMedian 같은 표본에서 템플릿 초안의 편집률 중앙값. 없으면 null
 * @param caveat             이 수치를 읽을 때의 한계. <b>숫자와 항상 붙여 다닌다</b>
 */
public record BlindReviewStats(int samples,
                               double medianEditRatio,
                               double medianDivergence,
                               int usedAsIs,
                               int rewritten,
                               int pairedSamples,
                               Double pairedModelMedian,
                               Double pairedBaselineMedian,
                               List<String> caveat) {

    static BlindReviewStats empty() {
        return new BlindReviewStats(0, 0, 0, 0, 0, 0, null, null,
                List.of("표본이 없습니다. 리뷰를 3단계까지 마쳐야 집계됩니다."));
    }

    /**
     * 활성화 조건 1번을 만족하나. <b>표본이 없으면 판정하지 않는다.</b>
     *
     * <p>{@code null} 은 "아직 모른다"다. {@code false} 로 내면 안 잰 것과 못 넘은 것이
     * 같아 보인다.
     */
    public Boolean beatsBaseline() {
        if (pairedModelMedian == null || pairedBaselineMedian == null) {
            return null;
        }
        return pairedModelMedian < pairedBaselineMedian;
    }
}
