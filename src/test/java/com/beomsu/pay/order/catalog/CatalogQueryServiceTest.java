package com.beomsu.pay.order.catalog;

import com.beomsu.pay.order.internal.OrderException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카탈로그 조회 서비스 단위 테스트.
 *
 * <p>핵심은 <b>정렬·필터가 리포지토리에 올바른 질의로 내려가는가</b>와, 목록/상세가 카테고리 이름과
 * 재고를 정확히 붙이는가다. 검색·카테고리·추천의 우선순위(검색어 &gt; 카테고리 &gt; 추천)도 고정한다.
 */
class CatalogQueryServiceTest {

    private ProductRepository productRepository;
    private CategoryRepository categoryRepository;
    private StockRepository stockRepository;
    private CatalogQueryService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        stockRepository = mock(StockRepository.class);
        service = new CatalogQueryService(productRepository, categoryRepository, stockRepository);
    }

    private static Product product(long id, String name, long price, String category, boolean featured) {
        return Product.of(id, name, price, category, "설명", "img", "브랜드", featured,
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    private static Pageable capturedPageable(ArgumentCaptor<Pageable> captor) {
        return captor.getValue();
    }

    @Test
    @DisplayName("카테고리 목록: 노출 순서를 유지하고 상품 수를 함께 센다")
    void categoriesIncludeProductCounts() {
        when(categoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                Category.of("digital", "디지털", "", 1),
                Category.of("food", "식품", "", 2)));
        when(productRepository.countByCategoryCode("digital")).thenReturn(7L);
        when(productRepository.countByCategoryCode("food")).thenReturn(5L);

        List<CategoryView> result = service.categories();

        assertThat(result).extracting(CategoryView::code).containsExactly("digital", "food");
        assertThat(result).extracting(CategoryView::productCount).containsExactly(7L, 5L);
    }

    @Test
    @DisplayName("기본 정렬은 신상품순(createdAt desc)")
    void defaultSortIsNewest() {
        when(productRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.products(null, null, null, null, 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(captor.capture());
        Sort sort = capturedPageable(captor).getSort();
        assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("가격 오름차순 정렬이 리포지토리 질의로 전달된다")
    void priceAscSortIsPassedDown() {
        when(productRepository.findByCategoryCode(eq("digital"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        service.products("digital", null, null, "price_asc", 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findByCategoryCode(eq("digital"), captor.capture());
        assertThat(capturedPageable(captor).getSort().getOrderFor("price").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("검색어는 앞뒤 공백을 제거해 상품명·브랜드에 함께 건다")
    void searchTrimsKeyword() {
        when(productRepository.findByNameContainingOrBrandContaining(anyString(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.products(null, "  이어폰  ", null, "newest", 0, 20);

        verify(productRepository).findByNameContainingOrBrandContaining(eq("이어폰"), eq("이어폰"), any());
    }

    @Test
    @DisplayName("검색어가 있으면 카테고리·추천보다 우선한다")
    void searchTakesPrecedence() {
        when(productRepository.findByNameContainingOrBrandContaining(anyString(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.products("digital", "이어폰", true, "newest", 0, 20);

        verify(productRepository).findByNameContainingOrBrandContaining(anyString(), anyString(), any());
        verify(productRepository, org.mockito.Mockito.never()).findByCategoryCode(anyString(), any());
    }

    @Test
    @DisplayName("추천 필터는 featured=true 상품만 요청한다")
    void featuredFilter() {
        when(productRepository.findByFeaturedTrue(any())).thenReturn(new PageImpl<>(List.of()));

        service.products(null, null, true, "newest", 0, 20);

        verify(productRepository).findByFeaturedTrue(any());
    }

    @Test
    @DisplayName("페이지 크기는 60으로 상한이 걸린다")
    void pageSizeIsCapped() {
        when(productRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        service.products(null, null, null, "newest", 0, 999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).findAll(captor.capture());
        assertThat(capturedPageable(captor).getPageSize()).isEqualTo(60);
    }

    @Test
    @DisplayName("목록 항목에 카테고리 이름과 재고 상태를 붙인다 — 재고 0은 품절")
    void summaryCarriesCategoryNameAndStock() {
        when(categoryRepository.findAll()).thenReturn(List.of(Category.of("digital", "디지털", "", 1)));
        when(productRepository.findAll(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product(4, "이어버드", 189000, "digital", true))));
        when(stockRepository.findByProductIdIn(List.of(4L))).thenReturn(List.of(Stock.of(4, 0)));

        ProductPageView page = service.products(null, null, null, "newest", 0, 20);

        assertThat(page.items()).hasSize(1);
        assertThat(page.items().get(0).categoryName()).isEqualTo("디지털");
        assertThat(page.items().get(0).inStock()).isFalse();
    }

    @Test
    @DisplayName("상세: 없는 상품은 PRODUCT_NOT_FOUND로 404가 된다")
    void productNotFound() {
        when(productRepository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.product(999L))
                .isInstanceOfSatisfying(OrderException.class,
                        ex -> assertThat(ex.code()).isEqualTo("PRODUCT_NOT_FOUND"));
    }

    @Test
    @DisplayName("상세: 재고 행이 없으면 품절로 단정하지 않는다(있음으로 본다)")
    void detailTreatsMissingStockAsInStock() {
        when(productRepository.findById(4L)).thenReturn(Optional.of(product(4, "이어버드", 189000, "digital", true)));
        when(categoryRepository.findAll()).thenReturn(List.of(Category.of("digital", "디지털", "", 1)));
        when(stockRepository.findById(4L)).thenReturn(Optional.empty());

        ProductDetailView detail = service.product(4L);

        assertThat(detail.categoryName()).isEqualTo("디지털");
        assertThat(detail.description()).isEqualTo("설명");
        assertThat(detail.inStock()).isTrue();
    }
}
