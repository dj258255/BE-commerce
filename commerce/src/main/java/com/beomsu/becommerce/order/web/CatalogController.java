package com.beomsu.becommerce.order.web;

import com.beomsu.becommerce.order.catalog.CatalogQueryService;
import com.beomsu.becommerce.order.catalog.CategoryView;
import com.beomsu.becommerce.order.catalog.FacetView;
import com.beomsu.becommerce.order.catalog.ProductDetailView;
import com.beomsu.becommerce.order.catalog.ProductPageView;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 카탈로그 조회 REST 컨트롤러 — 쇼핑몰 화면의 공개 읽기 표면.
 *
 * <p>상품 탐색은 로그인 없이 되어야 하므로 인증을 요구하지 않는다(SecurityConfig가 명시적으로
 * 개방한다). 쓰기 표면은 없다 — 가격·재고의 권위는 주문·결제 경로가 그대로 쥔다.
 *
 * <p>목록은 {@code q}(검색어)·{@code category}(카테고리)·{@code featured}(추천)·{@code colour}(색상)·
 * {@code productType}(종류)·{@code minPrice}·{@code maxPrice}(가격 범위)로 좁히고,
 * {@code sort}(newest·price_asc·price_desc·name)·{@code page}·{@code size}로 정렬·페이지네이션한다.
 * {@code q}가 있으면 다른 필터를 덮어쓴다. {@code category}는 대분류·중분류를 모두 받는다.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogQueryService catalogQueryService;

    public CatalogController(CatalogQueryService catalogQueryService) {
        this.catalogQueryService = catalogQueryService;
    }

    /**
     * 카테고리 목록. 기본은 **대분류만**이고, {@code ?tree=true}면 중분류까지 부모 다음 순서로 편다.
     * 기본 동작을 바꾸지 않는 이유: 기존 화면(헤더 내비게이션·홈 칩)이 이 응답을 그대로 쓴다.
     */
    @GetMapping("/categories")
    public List<CategoryView> categories(@RequestParam(required = false) Boolean tree) {
        return Boolean.TRUE.equals(tree) ? catalogQueryService.categoryTree() : catalogQueryService.categories();
    }

    @GetMapping("/products")
    public ProductPageView products(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) String colour,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalogQueryService.products(category, q, featured, colour, productType, minPrice, maxPrice,
                inStock, sort, page, size);
    }

    /**
     * 필터 패널용 패싯 — 색상·상품 종류의 값과 개수.
     *
     * <p>각 패싯은 자기 축을 뺀 나머지 필터만 적용한다(색상 패싯은 색상 없이, 종류 패싯은 종류 없이).
     * 그래야 한 값을 고른 상태에서도 다른 값의 개수가 보인다. {@code /products}와 같은 필터를 받는다.
     * 검색어({@code q})가 있으면 그 검색 결과의 패싯이다(#244).
     */
    @GetMapping("/products/facets")
    public FacetView facets(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean inStock,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(required = false) String colour,
            @RequestParam(required = false) String productType,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice) {
        if (q != null && !q.isBlank()) {
            return catalogQueryService.facets(q, category, colour, productType, minPrice, maxPrice, inStock);
        }
        return catalogQueryService.facets(category, featured, colour, productType, minPrice, maxPrice);
    }

    @GetMapping("/products/{productId}")
    public ProductDetailView product(@PathVariable long productId) {
        return catalogQueryService.product(productId);
    }
}
