package com.beomsu.becommerce.wishlist;

import com.beomsu.becommerce.order.ProductCatalogFacts;
import com.beomsu.becommerce.order.ProductCatalogFacts.ProductCardFacts;
import com.beomsu.becommerce.wishlist.internal.WishlistException;
import com.beomsu.becommerce.wishlist.internal.WishlistItem;
import com.beomsu.becommerce.wishlist.internal.WishlistRepository;
import com.beomsu.becommerce.wishlist.internal.WishlistService;
import com.beomsu.becommerce.wishlist.internal.WishlistView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 위시리스트의 계약을 고정한다 — 특히 <b>멱등</b>과 <b>없는 상품</b>.
 */
class WishlistServiceTest {

    private static final long USER = 1L;
    private static final long PRODUCT = 4L;

    private WishlistRepository wishlistRepository;
    private ProductCatalogFacts productCatalogFacts;
    private WishlistService service;

    @BeforeEach
    void setUp() {
        wishlistRepository = mock(WishlistRepository.class);
        productCatalogFacts = mock(ProductCatalogFacts.class);
        service = new WishlistService(wishlistRepository, productCatalogFacts);
    }

    private ProductCardFacts card(long productId) {
        return new ProductCardFacts(productId, "테스트 상품", 10_000, "H&M", "/uploads/x.jpg", true);
    }

    @Test
    @DisplayName("처음 찜하면 저장하고 카드 값을 담아 돌려준다")
    void addsNewItem() {
        when(productCatalogFacts.find(PRODUCT)).thenReturn(Optional.of(card(PRODUCT)));
        when(wishlistRepository.findByUserIdAndProductId(USER, PRODUCT)).thenReturn(Optional.empty());
        when(wishlistRepository.save(any(WishlistItem.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WishlistView view = service.add(USER, PRODUCT);

        assertThat(view.productId()).isEqualTo(PRODUCT);
        assertThat(view.name()).isEqualTo("테스트 상품");
        assertThat(view.available()).isTrue();
        verify(wishlistRepository).save(any(WishlistItem.class));
    }

    @Test
    @DisplayName("이미 찜한 상품을 또 찜하면 저장하지 않고 그때의 addedAt을 그대로 돌려준다")
    void addIsIdempotent() {
        Instant firstAddedAt = Instant.parse("2026-09-01T00:00:00Z");
        WishlistItem existing = WishlistItem.of(USER, PRODUCT);
        when(productCatalogFacts.find(PRODUCT)).thenReturn(Optional.of(card(PRODUCT)));
        when(wishlistRepository.findByUserIdAndProductId(USER, PRODUCT)).thenReturn(Optional.of(existing));

        WishlistView view = service.add(USER, PRODUCT);

        // 두 번째 요청은 아무것도 쓰지 않는다 — 유니크 제약이 아니라 사전 조회가 흡수한다.
        verify(wishlistRepository, never()).save(any(WishlistItem.class));
        // 지금 시각으로 덮으면 거짓말이 된다. 실제로 찜한 시각을 그대로 돌려준다.
        assertThat(view.addedAt()).isEqualTo(existing.getCreatedAt());
        assertThat(view.addedAt()).isAfter(firstAddedAt);
    }

    @Test
    @DisplayName("없는 상품을 찜하면 PRODUCT_NOT_FOUND — 카탈로그와 같은 코드를 쓴다")
    void rejectsMissingProduct() {
        when(productCatalogFacts.find(anyLong())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.add(USER, 999_999_999L))
                .isInstanceOf(WishlistException.class)
                .hasMessageContaining("999999999")
                .extracting(e -> ((WishlistException) e).code())
                .isEqualTo("PRODUCT_NOT_FOUND");

        verify(wishlistRepository, never()).save(any(WishlistItem.class));
    }

    @Test
    @DisplayName("삭제는 멱등 — 찜하지 않은 상품을 지워도 예외 없이 끝난다")
    void removeIsIdempotent() {
        when(wishlistRepository.deleteByUserIdAndProductId(USER, PRODUCT)).thenReturn(0L);

        service.remove(USER, PRODUCT);

        // 지운 행 수를 판정에 쓰지 않는다는 것이 계약이다 — 0이어도 성공이다.
        verify(wishlistRepository).deleteByUserIdAndProductId(USER, PRODUCT);
    }

    @Test
    @DisplayName("목록은 상품 값을 붙이고, 상품이 사라진 찜은 available=false로 남긴다")
    void listsWithProductCardsAndKeepsMissingOnes() {
        WishlistItem live = WishlistItem.of(USER, PRODUCT);
        WishlistItem gone = WishlistItem.of(USER, 777L);
        when(wishlistRepository.findByUserIdOrderByIdDesc(USER)).thenReturn(List.of(gone, live));
        when(productCatalogFacts.findAll(List.of(777L, PRODUCT))).thenReturn(List.of(card(PRODUCT)));

        List<WishlistView> views = service.list(USER);

        assertThat(views).hasSize(2);
        // 순서는 저장소가 준 대로(최근 찜 먼저) 유지된다.
        assertThat(views.get(0).productId()).isEqualTo(777L);
        assertThat(views.get(0).available()).isFalse();
        assertThat(views.get(0).name()).isNull();
        assertThat(views.get(1).productId()).isEqualTo(PRODUCT);
        assertThat(views.get(1).available()).isTrue();
    }

    @Test
    @DisplayName("찜이 없으면 상품 조회를 하지 않는다")
    void emptyListDoesNotHitCatalog() {
        when(wishlistRepository.findByUserIdOrderByIdDesc(USER)).thenReturn(List.of());

        assertThat(service.list(USER)).isEmpty();
        verify(productCatalogFacts, never()).findAll(any());
    }
}
