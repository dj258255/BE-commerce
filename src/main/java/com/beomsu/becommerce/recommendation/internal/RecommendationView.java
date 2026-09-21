package com.beomsu.becommerce.recommendation.internal;

import java.util.List;

/**
 * 추천 응답 — 상품 목록과 <b>그 목록이 어디서 왔고, 제약을 언제 확인했는지</b>.
 *
 * <p>{@code source}가 E3(coverage)의 원천이고, E4의 필드들이 이 실험의 원천이다.
 * <ul>
 *   <li>{@code constraintPolicy} — 어떤 시점에 확인했는가(정책이 곧 시점이다)</li>
 *   <li>{@code filteredByConstraint} — 확인이 <b>몇 개를 뺐는가</b>. 0이면 확인이 할 일이 없었다는
 *       뜻이고, 그때 위반율이 0인 것은 확인 덕이 아니다</li>
 *   <li>{@code snapshotAgeMs} — 확인에 쓴 사실이 응답 시점에 얼마나 낡았는가(확인을 안 하면 null).
 *       이것이 E4의 "stale 창"이다</li>
 *   <li>{@code changesInWindow} — 그 창 안에서 사실이 몇 번 바뀌었는가. 위반율과 함께 읽어야
 *       "확인이 잘 해서 낮은 것"과 "바뀐 게 없어서 낮은 것"이 구분된다</li>
 *   <li>{@code violations} — <b>최종 목록을 다시 대조한</b> 결과(실험 계기). 정책이 스스로 보고하는
 *       값이 아니라 바깥에서 센 값이다 — 검증이지 주장이 아니다</li>
 * </ul>
 *
 * <p><b>{@code servingMs}·{@code checkMs}·{@code auditMs}를 나눠서 준다.</b> 정책 경로 전체
 * ({@code servingMs})에는 모델 지연(50ms)이 들어 있어 확인 비용이 그 안에 묻힌다 — 그래서
 * <b>확인에 쓴 시간만</b> 따로 {@code checkMs}로 낸다. 이 값이 E4 후속의 비용 축이다(확인 0회 / 1회 /
 * 1회 / 2회가 여기서 갈린다). 계기(위반 검사)는 정책과 무관하게 모든 요청에 붙으므로 정책 간 비교에서
 * 상수지만, 절대 지연에는 더해진다 — 합쳐서 보고하면 <b>계기가 정책의 비용처럼 보인다</b>.
 */
public record RecommendationView(long userId,
                                 List<Long> items,
                                 String source,
                                 String fallbackReason,
                                 int contextItems,
                                 long modelMs,
                                 long servingMs,
                                 long checkMs,
                                 String constraintPolicy,
                                 int filteredByConstraint,
                                 Long snapshotAgeMs,
                                 Long changesInWindow,
                                 int violations,
                                 long auditMs) {

    /** 모델이 만들었다. */
    public static final String SOURCE_MODEL = "MODEL";
    /** 개인화를 포기하고 인기 상품으로 답했다 — 실패가 아니라 설계된 응답이다. */
    public static final String SOURCE_FALLBACK = "FALLBACK";
}
