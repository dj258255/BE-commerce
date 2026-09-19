package com.beomsu.pay.order.web;

import com.beomsu.pay.order.catalog.CatalogQueryService;
import com.beomsu.pay.order.catalog.CategoryView;
import com.beomsu.pay.order.catalog.ProductDetailView;
import com.beomsu.pay.order.catalog.ProductPageView;
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
 * <p>목록은 {@code q}(검색어)·{@code category}(카테고리)·{@code featured}(추천) 중 하나로 좁히고,
 * {@code sort}(newest·price_asc·price_desc·name)·{@code page}·{@code size}로 정렬·페이지네이션한다.
 */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {

    private final CatalogQueryService catalogQueryService;

    public CatalogController(CatalogQueryService catalogQueryService) {
        this.catalogQueryService = catalogQueryService;
    }

    @GetMapping("/categories")
    public List<CategoryView> categories() {
        return catalogQueryService.categories();
    }

    @GetMapping("/products")
    public ProductPageView products(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean featured,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return catalogQueryService.products(category, q, featured, sort, page, size);
    }

    @GetMapping("/products/{productId}")
    public ProductDetailView product(@PathVariable long productId) {
        return catalogQueryService.product(productId);
    }
}
