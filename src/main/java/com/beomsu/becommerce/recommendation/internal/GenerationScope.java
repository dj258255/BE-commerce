package com.beomsu.becommerce.recommendation.internal;

/**
 * 홈 한 페이지를 <b>어떻게 만드는가</b> — E5의 독립변수.
 *
 * <p>모델이 만드는 것은 상품 목록 하나가 아니라 <b>페이지</b>다. 그 페이지를 만드는 방식이 셋이고,
 * 셋의 차이는 <b>생성 구간이 얼마나 길어지는가</b>다 — autoregressive 생성은 <b>앞 항목이 정해져야
 * 다음 항목을 만들 수 있으므로</b> 한 요청 안에서 직렬이다(같은 요청의 항목들을 병렬로 못 만든다).
 *
 * <ul>
 *   <li>{@link #RANKING} — 후보를 <b>한 번에 점수 매겨</b> 상위 K 를 고른다. 생성 구간이 항목 수와
 *       무관하게 일정하다. 대신 항목 사이의 <b>일관성</b>은 없다(각 항목이 서로를 모른다)</li>
 *   <li>{@link #PREFIX_AR_TOP_K} — 앞 {@code arPrefix}개만 AR 로 만들고 나머지는 랭킹으로 채운다.
 *       직렬 구간이 앞부분으로 잘린다 — <b>일관성이 중요한 자리만 비싸게 사는</b> 절충이다</li>
 *   <li>{@link #FULL_AR} — 페이지 전체를 AR 로 만든다. 가장 일관되지만 <b>직렬 구간이 항목 수에
 *       비례</b>해 길어진다</li>
 * </ul>
 *
 * <p><b>품질은 이 실험이 재지 않는다.</b> "일관성"은 시스템 지표로 대신할 수 없다 — 여기서 재는 것은
 * 범위를 늘릴 때 <b>같은 하드웨어가 무엇을 내주는가</b>(생성 시간·처리량·예산 몫)뿐이고, 그 경계는
 * 리포트에 명시한다.
 *
 * <p><b>왜 비용 모델이 enum 안에 있나</b>: 모델 스텁과 과부하 정책(대기 예상)이 <b>같은 함수</b>를 봐야
 * 한다. 정책은 모델 지연을 알아야 대기 예상을 계산하는데(Little의 법칙), 그 지연이 범위에 따라
 * 달라지므로 둘이 다른 값을 쓰면 <b>정책이 잘못된 지연으로 판단</b>해 실험이 오염된다.
 * 이 함수는 순수해서 단위 테스트로 고정할 수 있다.
 */
public enum GenerationScope {

    RANKING,
    PREFIX_AR_TOP_K,
    FULL_AR;

    /**
     * 이 범위가 만드는 <b>모델 지연</b>(ms) — 기준 지연 + 직렬 생성 구간.
     *
     * <p>직렬 항목 수는 범위가 정한다: 랭킹은 0, 앞부분 AR 은 {@code arPrefix}(단 결과 크기를 넘지 않게
     * 자른다 — 만들 항목보다 많이 생성할 수는 없다), 전체 AR 은 {@code resultSize}.
     */
    public long estimatedLatencyMs(long baseLatencyMs, int resultSize, int arPrefix, long perItemMs) {
        int sequential = switch (this) {
            case RANKING -> 0;
            case PREFIX_AR_TOP_K -> Math.min(Math.max(arPrefix, 0), Math.max(resultSize, 0));
            case FULL_AR -> Math.max(resultSize, 0);
        };
        return Math.max(baseLatencyMs, 0) + (long) sequential * Math.max(perItemMs, 0);
    }

    /** 이 범위가 직렬로 생성하는 항목 수 — 리포트가 "직렬 구간이 몇 항목인가"를 함께 적기 위해 쓴다. */
    public int sequentialItems(int resultSize, int arPrefix) {
        return switch (this) {
            case RANKING -> 0;
            case PREFIX_AR_TOP_K -> Math.min(Math.max(arPrefix, 0), Math.max(resultSize, 0));
            case FULL_AR -> Math.max(resultSize, 0);
        };
    }
}
