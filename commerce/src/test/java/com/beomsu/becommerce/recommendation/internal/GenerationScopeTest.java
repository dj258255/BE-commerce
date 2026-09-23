package com.beomsu.becommerce.recommendation.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 생성 범위의 <b>비용 모델</b>을 고정한다 — E5의 독립변수가 실제로 다른 지연을 내는가.
 *
 * <p>이 함수가 순수한 이유가 여기 있다: 모델 스텁(재우는 시간)과 과부하 정책(대기 예상)이
 * <b>같은 함수</b>를 본다. 둘이 다른 값을 쓰면 정책이 잘못된 지연으로 판단해 실험이 조용히 오염된다 —
 * 지연을 흔들어 보지 않고는 알 수 없는 종류의 오염이다.
 */
class GenerationScopeTest {

    private static final long BASE = 50;
    private static final int RESULT_SIZE = 12;
    private static final int PREFIX = 4;
    private static final long PER_ITEM = 15;

    @Test
    @DisplayName("랭킹은 항목 수와 무관하다 — 한 번에 점수 매기므로 직렬 구간이 없다")
    void rankingDoesNotDependOnResultSize() {
        assertThat(GenerationScope.RANKING.estimatedLatencyMs(BASE, RESULT_SIZE, PREFIX, PER_ITEM))
                .isEqualTo(BASE);
        assertThat(GenerationScope.RANKING.estimatedLatencyMs(BASE, 100, PREFIX, PER_ITEM))
                .as("항목이 100개여도 랭킹은 같다")
                .isEqualTo(BASE);
        assertThat(GenerationScope.RANKING.sequentialItems(RESULT_SIZE, PREFIX)).isZero();
    }

    @Test
    @DisplayName("앞부분 AR 은 직렬 구간이 앞 K 개로 잘린다 — 일관성이 필요한 자리만 비싸게 산다")
    void prefixArCostsOnlyThePrefix() {
        assertThat(GenerationScope.PREFIX_AR_TOP_K.estimatedLatencyMs(BASE, RESULT_SIZE, PREFIX, PER_ITEM))
                .isEqualTo(BASE + PREFIX * PER_ITEM);
        assertThat(GenerationScope.PREFIX_AR_TOP_K.sequentialItems(RESULT_SIZE, PREFIX)).isEqualTo(PREFIX);
    }

    @Test
    @DisplayName("전체 AR 은 직렬 구간이 결과 크기에 비례한다 — 페이지가 길수록 선형으로 느려진다")
    void fullArCostsEveryItem() {
        assertThat(GenerationScope.FULL_AR.estimatedLatencyMs(BASE, RESULT_SIZE, PREFIX, PER_ITEM))
                .isEqualTo(BASE + RESULT_SIZE * PER_ITEM);
        assertThat(GenerationScope.FULL_AR.estimatedLatencyMs(BASE, 24, PREFIX, PER_ITEM))
                .as("결과가 두 배면 직렬 구간도 두 배다")
                .isEqualTo(BASE + 24 * PER_ITEM);
    }

    @Test
    @DisplayName("범위를 넓힐수록 지연이 커진다 — 이것이 E5가 재는 교환비다")
    void widerScopeCostsMore() {
        long ranking = GenerationScope.RANKING.estimatedLatencyMs(BASE, RESULT_SIZE, PREFIX, PER_ITEM);
        long prefix = GenerationScope.PREFIX_AR_TOP_K.estimatedLatencyMs(BASE, RESULT_SIZE, PREFIX, PER_ITEM);
        long full = GenerationScope.FULL_AR.estimatedLatencyMs(BASE, RESULT_SIZE, PREFIX, PER_ITEM);

        assertThat(ranking).isLessThan(prefix);
        assertThat(prefix).isLessThan(full);
    }

    @Test
    @DisplayName("앞부분 K 가 결과 크기를 넘어도 결과 크기까지만 생성한다 — 만들 항목보다 많이 만들 수 없다")
    void prefixIsClampedToResultSize() {
        assertThat(GenerationScope.PREFIX_AR_TOP_K.estimatedLatencyMs(BASE, 3, 10, PER_ITEM))
                .isEqualTo(BASE + 3 * PER_ITEM);
        assertThat(GenerationScope.PREFIX_AR_TOP_K.sequentialItems(3, 10)).isEqualTo(3);
    }

    @Test
    @DisplayName("직렬 항목 수만 보고 계산한다 — 항목당 비용이 0이면 범위는 공짜다")
    void zeroPerItemMakesScopesEqualInCost() {
        assertThat(GenerationScope.FULL_AR.estimatedLatencyMs(BASE, RESULT_SIZE, PREFIX, 0))
                .isEqualTo(BASE);
    }
}
