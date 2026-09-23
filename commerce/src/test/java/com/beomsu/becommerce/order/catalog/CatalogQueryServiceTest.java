package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderException;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 카탈로그 조회 서비스 단위 테스트.
 *
 * <p>핵심은 <b>정렬·필터가 리포지토리에 올바른 질의로 내려가는가</b>와, 목록/상세가 카테고리 이름과
 * 재고를 정확히 붙이는가다. 여섯 필터는 이제 하나의 {@code search(...)} 로 내려가므로, 대분류/중분류가
 * 각각 어느 인자에 앉는지와 색상·종류·가격이 그대로 전달되는지를 고정한다.
 * 검색어({@code q})의 우선순위도 그대로다 — 검색어가 있으면 {@code search(...)} 는 아예 불리지 않는다.
 */
class CatalogQueryServiceTest {

    private ProductRepository productRepository;
    private CategoryRepository categoryRepository;
    private StockRepository stockRepository;
    private ProductReviewRepository reviewRepository;
    private FacetCache facetCache;
    private CatalogQueryService service;

    @BeforeEach
    void setUp() {
        productRepository = mock(ProductRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        stockRepository = mock(StockRepository.class);
        // 리뷰는 기본이 빈 목록(Mockito 의 List 기본값) — 리뷰 계약은 ReviewSyntheticGuardTest 가 본다.
        reviewRepository = mock(ProductReviewRepository.class);
        // 캐시는 **통과시키는 목**으로 둔다 — 이 테스트가 보는 것은 "질의가 올바른 인자로 내려가는가"지
        // 캐시 동작이 아니다. 캐시 자체의 계약은 FacetCacheTest 가 본다.
        facetCache = mock(FacetCache.class);
        when(facetCache.get(anyString(), any()))
                .thenAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get());
        service = new CatalogQueryService(productRepository, categoryRepository, stockRepository,
                reviewRepository, facetCache,
                new com.beomsu.becommerce.order.catalog.search.LikeProductSearch(productRepository, false));
    }

    private static Product product(long id, String name, long price, String category, boolean featured) {
        return Product.of(id, name, price, category, "설명", "img", "브랜드", featured,
                Instant.parse("2026-09-01T00:00:00Z"));
    }

    /** 목록·필터가 도는 경로의 기본 스텁 — 통합 질의가 빈 페이지를 돌려준다. */
    private void stubSearch() {
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of()));
    }

    private static Pageable capturedPageable(ArgumentCaptor<Pageable> captor) {
        return captor.getValue();
    }

    @Test
    @DisplayName("카테고리 목록: 대분류만, 노출 순서를 유지하고 상품 수를 함께 센다")
    void categoriesIncludeProductCounts() {
        when(categoryRepository.findByParentCodeIsNullOrderBySortOrderAsc()).thenReturn(List.of(
                Category.of("digital", "디지털", "", 1),
                Category.of("food", "식품", "", 2)));
        when(productRepository.countByCategoryCode("digital")).thenReturn(7L);
        when(productRepository.countByCategoryCode("food")).thenReturn(5L);

        List<CategoryView> result = service.categories();

        assertThat(result).extracting(CategoryView::code).containsExactly("digital", "food");
        assertThat(result).extracting(CategoryView::productCount).containsExactly(7L, 5L);
        assertThat(result).allMatch(v -> v.parentCode() == null);
    }

    @Test
    @DisplayName("카테고리 트리: 부모 **다음에** 그 자식들이 오고, 자식 수는 자기 것으로 센다")
    void categoryTreePutsChildrenAfterTheirParent() {
        when(categoryRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(
                Category.of("ladieswear", "여성복", "", 1),
                Category.of("ladieswear.knitwear", "니트", "", 1, "ladieswear"),
                Category.of("ladieswear.dresses", "드레스", "", 2, "ladieswear"),
                Category.of("menswear", "남성복", "", 2),
                Category.of("menswear.shirts", "셔츠", "", 1, "menswear")));
        when(productRepository.countByCategoryCode("ladieswear")).thenReturn(100L);
        when(productRepository.countByCategoryCode("menswear")).thenReturn(40L);
        when(productRepository.countBySubcategoryCode("ladieswear.knitwear")).thenReturn(60L);
        when(productRepository.countBySubcategoryCode("ladieswear.dresses")).thenReturn(40L);
        when(productRepository.countBySubcategoryCode("menswear.shirts")).thenReturn(40L);

        List<CategoryView> tree = service.categoryTree();

        // 전역 sort_order가 부모·자식에 겹치므로, 평평하게 정렬하면 자식이 남의 부모 밑으로 섞인다.
        assertThat(tree).extracting(CategoryView::code)
                .containsExactly("ladieswear", "ladieswear.knitwear", "ladieswear.dresses",
                        "menswear", "menswear.shirts");

        // 대분류는 category_code로, 중분류는 subcategory_code로 센다.
        verify(productRepository).countByCategoryCode("ladieswear");
        verify(productRepository).countBySubcategoryCode("ladieswear.knitwear");
        verify(productRepository, org.mockito.Mockito.never()).countBySubcategoryCode("ladieswear");
    }

    @Test
    @DisplayName("중분류 코드는 subcategoryCode 인자에만 앉고 categoryCode는 비운다")
    void childCategoryFilterUsesSubcategoryArgument() {
        when(categoryRepository.findById("ladieswear.knitwear"))
                .thenReturn(Optional.of(Category.of("ladieswear.knitwear", "니트", "", 1, "ladieswear")));
        stubSearch();

        service.products("ladieswear.knitwear", null, null, null, null, null, null, "newest", 0, 20);

        verify(productRepository).search(isNull(), eq("ladieswear.knitwear"), isNull(), isNull(),
                isNull(), isNull(), isNull(), any(Pageable.class));
        verify(productRepository, org.mockito.Mockito.never())
                .findByNameContainingOrBrandContaining(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("대분류 코드는 categoryCode 인자에만 앉고 subcategoryCode는 비운다")
    void parentCategoryFilterUsesCategoryArgument() {
        when(categoryRepository.findById("ladieswear"))
                .thenReturn(Optional.of(Category.of("ladieswear", "여성복", "", 1)));
        stubSearch();

        service.products("ladieswear", null, null, null, null, null, null, "newest", 0, 20);

        verify(productRepository).search(eq("ladieswear"), isNull(), isNull(), isNull(),
                isNull(), isNull(), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("색상·종류·가격 범위가 각 인자에 그대로 내려간다")
    void colourTypeAndPriceLandOnTheirArguments() {
        when(categoryRepository.findById("ladieswear"))
                .thenReturn(Optional.of(Category.of("ladieswear", "여성복", "", 1)));
        stubSearch();

        service.products("ladieswear", null, null, "black", "Dress", 20_000L, 30_000L, "newest", 0, 20);

        verify(productRepository).search(eq("ladieswear"), isNull(), eq("black"), eq("Dress"),
                eq(20_000L), eq(30_000L), isNull(), any(Pageable.class));
    }

    @Test
    @DisplayName("추천 필터(featured)는 search의 featured 인자로 내려간다")
    void featuredFilterGoesThroughSearch() {
        stubSearch();

        service.products(null, null, true, null, null, null, null, "newest", 0, 20);

        verify(productRepository).search(isNull(), isNull(), isNull(), isNull(),
                isNull(), isNull(), eq(true), any(Pageable.class));
    }

    @Test
    @DisplayName("기본 정렬은 신상품순(createdAt desc)")
    void defaultSortIsNewest() {
        stubSearch();

        service.products(null, null, null, null, null, null, null, null, 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).search(any(), any(), any(), any(), any(), any(), any(), captor.capture());
        Sort sort = capturedPageable(captor).getSort();
        assertThat(sort.getOrderFor("createdAt").getDirection()).isEqualTo(Sort.Direction.DESC);
    }

    @Test
    @DisplayName("가격 오름차순 정렬이 리포지토리 질의로 전달된다")
    void priceAscSortIsPassedDown() {
        stubSearch();

        service.products(null, null, null, null, null, null, null, "price_asc", 0, 20);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).search(any(), any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(capturedPageable(captor).getSort().getOrderFor("price").getDirection())
                .isEqualTo(Sort.Direction.ASC);
    }

    @Test
    @DisplayName("검색어는 앞뒤 공백을 제거해 상품명·브랜드에 함께 건다")
    void searchTrimsKeyword() {
        when(productRepository.findByNameContainingOrBrandContaining(anyString(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.products(null, "  이어폰  ", null, null, null, null, null, "newest", 0, 20);

        verify(productRepository).findByNameContainingOrBrandContaining(eq("이어폰"), eq("이어폰"), any());
    }

    @Test
    @DisplayName("관련도 엔진이면 정렬을 고르지 않은 검색은 엔진 순서를 지키며 DB 에서 상품을 채운다(#236)")
    void relevanceEngineOrderIsKept() {
        com.beomsu.becommerce.order.catalog.search.ProductSearch engine =
                new com.beomsu.becommerce.order.catalog.search.ProductSearch() {
                    public SearchPage search(String query, int page, int size) {
                        return new SearchPage(List.of(3L, 1L, 99L), 3);
                    }
                    public String engine() { return "test"; }
                    public boolean ranksByRelevance() { return true; }
                };
        CatalogQueryService relevance = new CatalogQueryService(productRepository, categoryRepository,
                stockRepository, reviewRepository, facetCache, engine);
        // DB 는 순서를 모른다 — 1, 3 순서로 돌려주고, 99 는 색인에만 있고 DB 에서 지워진 상품이다
        when(productRepository.findAllById(any()))
                .thenReturn(List.of(product(1, "하나", 1000, "digital", false), product(3, "셋", 3000, "digital", false)));

        ProductPageView view = relevance.products(null, "셋", null, null, null, null, null, null, 0, 10);

        assertThat(view.items()).extracting(ProductSummaryView::productId).containsExactly(3L, 1L);
        verify(productRepository, org.mockito.Mockito.never()).findByNameContainingOrBrandContaining(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("검색어가 있으면 다른 모든 필터보다 우선한다 — search(...)는 불리지 않는다")
    void searchTakesPrecedence() {
        when(productRepository.findByNameContainingOrBrandContaining(anyString(), anyString(), any()))
                .thenReturn(new PageImpl<>(List.of()));

        service.products("ladieswear", "이어폰", true, "black", "Dress", 20_000L, 30_000L, "newest", 0, 20);

        verify(productRepository).findByNameContainingOrBrandContaining(anyString(), anyString(), any());
        verify(productRepository, org.mockito.Mockito.never())
                .search(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("페이지 크기는 60으로 상한이 걸린다")
    void pageSizeIsCapped() {
        stubSearch();

        service.products(null, null, null, null, null, null, null, "newest", 0, 999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(productRepository).search(any(), any(), any(), any(), any(), any(), any(), captor.capture());
        assertThat(capturedPageable(captor).getPageSize()).isEqualTo(60);
    }

    @Test
    @DisplayName("패싯: 색상 질의는 색상 필터 없이, 종류 질의는 종류 필터 없이 나머지 필터만 받는다")
    void facetsDropOwnAxisButHonourOtherFilters() {
        when(categoryRepository.findById("ladieswear"))
                .thenReturn(Optional.of(Category.of("ladieswear", "여성복", "", 1)));
        when(productRepository.colourFacet(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(productRepository.typeFacet(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.facets("ladieswear", null, "black", "Dress", 20_000L, 30_000L);

        // 색상 패싯은 색상 필터를 받지 않는다 — 종류·가격은 그대로 받는다.
        verify(productRepository).colourFacet(eq("ladieswear"), isNull(), eq("Dress"),
                eq(20_000L), eq(30_000L), isNull());
        // 종류 패싯은 종류 필터를 받지 않는다 — 색상·가격은 그대로 받는다.
        verify(productRepository).typeFacet(eq("ladieswear"), isNull(), eq("black"),
                eq(20_000L), eq(30_000L), isNull());
    }

    @Test
    @DisplayName("패싯: 중분류 코드는 subcategory 인자로 내려간다")
    void facetsResolveChildCategoryToSubcategoryArgument() {
        when(categoryRepository.findById("ladieswear.knitwear"))
                .thenReturn(Optional.of(Category.of("ladieswear.knitwear", "니트", "", 1, "ladieswear")));
        when(productRepository.colourFacet(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());
        when(productRepository.typeFacet(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of());

        service.facets("ladieswear.knitwear", null, null, null, null, null);

        verify(productRepository).colourFacet(isNull(), eq("ladieswear.knitwear"), isNull(),
                isNull(), isNull(), isNull());
    }

    @Test
    @DisplayName("패싯 뷰는 색상·종류 목록을 그대로 싣는다")
    void facetViewCarriesBothAxes() {
        when(categoryRepository.findById("ladieswear"))
                .thenReturn(Optional.of(Category.of("ladieswear", "여성복", "", 1)));
        FacetCount black = facet("black", "블랙", 100L);
        FacetCount dress = facet("Dress", "Dress", 42L);
        when(productRepository.colourFacet(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(black));
        when(productRepository.typeFacet(any(), any(), any(), any(), any(), any()))
                .thenReturn(List.of(dress));

        FacetView view = service.facets("ladieswear", null, null, null, null, null);

        assertThat(view.colours()).containsExactly(black);
        assertThat(view.productTypes()).containsExactly(dress);
    }

    /** 테스트용 FacetCount 스텁 — 인터페이스 투영이라 값만 담는 익명 구현으로 충분하다. */
    private static FacetCount facet(String code, String name, long count) {
        return new FacetCount() {
            @Override
            public String getCode() {
                return code;
            }

            @Override
            public String getName() {
                return name;
            }

            @Override
            public long getCount() {
                return count;
            }
        };
    }

    @Test
    @DisplayName("목록 항목에 카테고리 이름과 재고 상태를 붙인다 — 재고 0은 품절")
    void summaryCarriesCategoryNameAndStock() {
        when(categoryRepository.findAll()).thenReturn(List.of(Category.of("digital", "디지털", "", 1)));
        when(productRepository.search(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(product(4, "이어버드", 189000, "digital", true))));
        when(stockRepository.findByProductIdIn(List.of(4L))).thenReturn(List.of(Stock.of(4, 0)));

        ProductPageView page = service.products(null, null, null, null, null, null, null, "newest", 0, 20);

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
