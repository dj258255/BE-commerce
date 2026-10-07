package com.beomsu.becommerce.shorts;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * R25: 숏폼 영상-상품 다중 연결(연결·해제·멱등·개수 상한)을 검증한다.
 *
 * <p>상품이 카탈로그에 실존하는지는 {@code ShortsService}가 미리 확인하므로({@code ProductCatalogFacts})
 * 여기서는 {@link ShortVideo}가 스스로 지키는 규칙(중복 방지·상한)만 다룬다.
 */
class ShortVideoProductLinkingTest {

    private static final UploadMeta VALID_META = new UploadMeta(30, 10_000_000L, 1080, 1920, "video/mp4");

    private ShortVideo video() {
        return ShortVideo.upload(1L, "shorts/1/" + System.nanoTime(), VALID_META);
    }

    @Test
    @DisplayName("R25: 업로드 직후에는 연결된 상품이 없다")
    void startsWithNoLinkedProducts() {
        ShortVideo v = video();
        assertThat(v.getLinkedProductIds()).isEmpty();
    }

    @Test
    @DisplayName("R25: 상품을 연결하면 연결 목록에 담긴다")
    void linkProductAddsToList() {
        ShortVideo v = video();

        v.linkProduct(100L);
        v.linkProduct(200L);

        assertThat(v.getLinkedProductIds()).containsExactly(100L, 200L);
    }

    @Test
    @DisplayName("R25: 같은 상품을 다시 연결해도 중복 없이 멱등하다")
    void linkingSameProductTwiceIsIdempotent() {
        ShortVideo v = video();

        v.linkProduct(100L);
        v.linkProduct(100L);

        assertThat(v.getLinkedProductIds()).containsExactly(100L);
    }

    @Test
    @DisplayName("R25: 상품 연결 해제는 멱등 — 연결되지 않은 상품을 해제해도 성공한다")
    void unlinkingProductIsIdempotent() {
        ShortVideo v = video();
        v.linkProduct(100L);

        v.unlinkProduct(999L); // 연결된 적 없는 상품
        v.unlinkProduct(100L);
        v.unlinkProduct(100L); // 두 번째 해제도 성공해야 한다

        assertThat(v.getLinkedProductIds()).isEmpty();
    }

    @Test
    @DisplayName("R25: 연결 상품 id는 저장 순서와 무관하게 오름차순으로 조회된다")
    void linkedProductIdsAreSortedAscending() {
        ShortVideo v = video();

        v.linkProduct(300L);
        v.linkProduct(100L);
        v.linkProduct(200L);

        assertThat(v.getLinkedProductIds()).containsExactly(100L, 200L, 300L);
    }

    @Test
    @DisplayName("R25 경계: 정확히 MAX_LINKED_PRODUCTS(기본 10)개까지는 모두 연결된다")
    void exactlyMaxLinkedProductsIsAllowed() {
        ShortVideo v = video();

        for (long productId = 1; productId <= ShortVideo.MAX_LINKED_PRODUCTS; productId++) {
            v.linkProduct(productId);
        }

        assertThat(v.getLinkedProductIds()).hasSize(ShortVideo.MAX_LINKED_PRODUCTS);
    }

    @Test
    @DisplayName("R25 경계: 상한을 넘는 (max+1)번째 새 상품 연결은 TOO_MANY_LINKED_PRODUCTS로 거절된다")
    void exceedingMaxLinkedProductsIsRejected() {
        ShortVideo v = video();
        for (long productId = 1; productId <= ShortVideo.MAX_LINKED_PRODUCTS; productId++) {
            v.linkProduct(productId);
        }

        assertThatThrownBy(() -> v.linkProduct(999L))
                .isInstanceOf(ShortsException.class)
                .hasFieldOrPropertyWithValue("code", "TOO_MANY_LINKED_PRODUCTS");
        // 거절된 호출이 상태를 바꾸지 않았는지도 함께 본다.
        assertThat(v.getLinkedProductIds()).hasSize(ShortVideo.MAX_LINKED_PRODUCTS);
    }

    @Test
    @DisplayName("R25 경계: 가득 찬 상태에서도 이미 연결된 상품을 다시 연결하는 멱등 호출은 통과한다")
    void relinkingExistingProductPassesEvenWhenFull() {
        ShortVideo v = video();
        for (long productId = 1; productId <= ShortVideo.MAX_LINKED_PRODUCTS; productId++) {
            v.linkProduct(productId);
        }

        v.linkProduct(1L); // 이미 연결된 상품 — 상한에 걸리지 않아야 한다

        assertThat(v.getLinkedProductIds()).hasSize(ShortVideo.MAX_LINKED_PRODUCTS);
    }

    @Test
    @DisplayName("R25: 해제 후 빈 자리에는 다시 다른 상품을 연결할 수 있다")
    void canLinkAgainAfterUnlinkingWhenFull() {
        ShortVideo v = video();
        for (long productId = 1; productId <= ShortVideo.MAX_LINKED_PRODUCTS; productId++) {
            v.linkProduct(productId);
        }

        v.unlinkProduct(1L);
        v.linkProduct(999L);

        assertThat(v.getLinkedProductIds()).hasSize(ShortVideo.MAX_LINKED_PRODUCTS);
        assertThat(v.getLinkedProductIds()).contains(999L).doesNotContain(1L);
    }
}
