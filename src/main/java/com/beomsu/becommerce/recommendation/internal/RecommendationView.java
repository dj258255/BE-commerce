package com.beomsu.becommerce.recommendation.internal;

import java.util.List;

/**
 * 추천 응답 — 상품 목록과 <b>그 목록이 어디서 왔는지</b>.
 *
 * <p>{@code source}가 이 실험의 주 지표(coverage)의 원천이다. {@code MODEL}이면 개인화가 값을 냈고,
 * {@code FALLBACK}이면 포기했다. 응답 시간만 보면 둘이 구분되지 않으므로 <b>몸통에 밝힌다</b>.
 *
 * <p>{@code fallbackReason}은 왜 포기했는지다 — {@code REJECTED}(정책이 줄을 끊었다) /
 * {@code TIMEOUT}(모델 용량을 못 기다렸다) / {@code FAILED}(모델이 실패했다). 처방이 다르므로 나눈다.
 *
 * <p>{@code contextItems}는 모델에 넣은 최근 활동 수다. 이것이 0이면 모델이 개인화할 재료가 없었다는
 * 뜻이고, coverage가 높아도 <b>실제로는 개인화가 아니었을 수 있다</b> — 그래서 함께 돌려준다.
 */
public record RecommendationView(long userId,
                                 List<Long> items,
                                 String source,
                                 String fallbackReason,
                                 int contextItems,
                                 long modelMs,
                                 long servingMs) {

    /** 모델이 만들었다. */
    public static final String SOURCE_MODEL = "MODEL";
    /** 개인화를 포기하고 인기 상품으로 답했다 — 실패가 아니라 설계된 응답이다. */
    public static final String SOURCE_FALLBACK = "FALLBACK";
}
