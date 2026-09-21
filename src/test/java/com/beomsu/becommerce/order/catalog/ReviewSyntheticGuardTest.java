package com.beomsu.becommerce.order.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 합성 리뷰의 <b>격리 계약</b>을 코드로 박는다 — #168.
 *
 * <p>이 이슈의 위험은 "리뷰가 없다"가 아니라 <b>"합성 리뷰가 실데이터처럼 쓰이는 것"</b>이다.
 * 그래서 두 가지를 고정한다:
 * <ol>
 *   <li><b>평점을 집계하는 필드가 없다</b> — 상품·카드·상세 어디에도 평균 평점이 없어야 한다.
 *       하나 생기면 목록 정렬·화면 배지가 그 값을 품질 신호로 쓰기 시작한다</li>
 *   <li><b>리뷰를 내보낼 때 출처를 함께 내보낸다</b> — 화면이 합성임을 밝힐 근거가 응답에 있어야 한다</li>
 * </ol>
 *
 * <p>이 테스트가 깨지면 누군가 평점 집계를 **의도적으로** 추가한 것이다 — 그때는 ADR-046 을 먼저
 * 고쳐야 한다(합성 지표를 화면에 올리는 결정이므로).
 */
class ReviewSyntheticGuardTest {

    private static final List<String> AGGREGATE_HINTS =
            List.of("rating", "avg", "average", "score", "star", "reviewcount", "reviewcount");

    @Test
    @DisplayName("상품 카드·상세에는 평점 집계 필드가 없다 — 있으면 그 값이 품질 신호로 쓰인다")
    void noAggregateRatingFields() {
        assertNoAggregateField(ProductSummaryView.class);
        assertNoAggregateField(ProductDetailView.class);
    }

    @Test
    @DisplayName("리뷰는 출처를 함께 내보낸다 — 화면이 합성임을 밝힐 근거가 응답에 있어야 한다")
    void reviewsCarryTheirSource() {
        List<RecordComponent> components = Arrays.asList(ProductDetailView.ReviewView.class.getRecordComponents());
        assertThat(components).extracting(RecordComponent::getName)
                .contains("source", "rating", "body", "author", "createdAt");
    }

    @Test
    @DisplayName("상세 응답은 합성 여부를 사실로 밝힌다 — 프론트가 추측하면 언젠가 배지를 잊는다")
    void detailDeclaresSynthetic() {
        assertThat(Arrays.stream(ProductDetailView.class.getRecordComponents())
                .map(RecordComponent::getName)).contains("reviewsSynthetic");
    }

    private static void assertNoAggregateField(Class<?> record) {
        List<String> names = Arrays.stream(record.getRecordComponents())
                .map(c -> c.getName().toLowerCase(Locale.ROOT))
                .toList();
        for (String name : names) {
            // `rating` 은 **리뷰 한 건의 별점**(ReviewView)에는 허용된다 — 금지하는 것은 상품·카드에
            // 붙는 **집계**(평균·개수)다. 그래서 여기서는 상품 수준 record 만 검사한다.
            assertThat(AGGREGATE_HINTS)
                    .as("%s 에 집계성 필드가 생겼다: %s (ADR-046)", record.getSimpleName(), name)
                    .noneMatch(name::contains);
        }
    }
}
