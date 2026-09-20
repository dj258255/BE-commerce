package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 카탈로그 조회 애플리케이션 서비스 — 쇼핑몰 화면의 읽기 진입점.
 *
 * <p>쓰기 표면이 없다. 상품은 시드와 마이그레이션으로만 채워지고, 가격·재고의 권위는 여전히
 * 주문 생성({@code CheckoutService})이 쥔다. 여기서는 탐색에 필요한 것만 읽어 뷰로 넘긴다.
 *
 * <p>읽기 전용 트랜잭션이라 지연 로딩으로 커넥션을 오래 잡지 않는다. 목록에서 카테고리 이름과
 * 재고를 붙일 때는 페이지에 실린 상품 id로 <b>한 번에</b> 읽어 N+1을 피한다.
 */
@Service
@Transactional(readOnly = true)
public class CatalogQueryService {

    /** 한 번에 요청할 수 있는 최대 페이지 크기 — 큰 size로 전건을 훑는 요청을 막는다. */
    private static final int MAX_PAGE_SIZE = 60;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final StockRepository stockRepository;

    public CatalogQueryService(ProductRepository productRepository,
                               CategoryRepository categoryRepository,
                               StockRepository stockRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.stockRepository = stockRepository;
    }

    /** 카테고리 목록 — 노출 순서대로, 상품 수를 함께 센다. */
    public List<CategoryView> categories() {
        return categoryRepository.findAllByOrderBySortOrderAsc().stream()
                .map(c -> CategoryView.of(c, productRepository.countByCategoryCode(c.getCode())))
                .toList();
    }

    /**
     * 상품 목록 — 검색어·카테고리·추천 중 하나로 좁히고 정렬·페이지네이션을 적용한다.
     * 우선순위는 검색어 &gt; 카테고리 &gt; 추천 &gt; 전체다.
     */
    public ProductPageView products(String category, String q, Boolean featured, String sort,
                                    int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), sortOf(sort));
        Page<Product> result;
        if (q != null && !q.isBlank()) {
            String keyword = q.trim();
            result = productRepository.findByNameContainingOrBrandContaining(keyword, keyword, pageable);
        } else if (category != null && !category.isBlank()) {
            result = productRepository.findByCategoryCode(category, pageable);
        } else if (Boolean.TRUE.equals(featured)) {
            result = productRepository.findByFeaturedTrue(pageable);
        } else {
            result = productRepository.findAll(pageable);
        }
        return toPageView(result);
    }

    /** 상품 상세. 없는 상품은 주문 경로와 같은 코드(PRODUCT_NOT_FOUND)로 404를 낸다. */
    public ProductDetailView product(long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> OrderException.productNotFound(productId));
        return ProductDetailView.of(product, categoryNames().get(product.getCategoryCode()),
                inStock(product.getProductId()));
    }

    private ProductPageView toPageView(Page<Product> page) {
        Map<String, String> names = categoryNames();
        Map<Long, Integer> stockByProduct = stockByProduct(page.getContent());
        List<ProductSummaryView> items = page.getContent().stream()
                .map(p -> ProductSummaryView.of(p, names.get(p.getCategoryCode()),
                        stockByProduct.getOrDefault(p.getProductId(), 1) > 0))
                .toList();
        return ProductPageView.of(items, page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    private Map<String, String> categoryNames() {
        return categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getCode, Category::getName));
    }

    /** 페이지에 실린 상품들의 재고를 한 번에 읽는다. 행이 없으면 재고 부족의 근거가 아니므로 있음으로 본다. */
    private Map<Long, Integer> stockByProduct(List<Product> products) {
        List<Long> ids = products.stream().map(Product::getProductId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return stockRepository.findByProductIdIn(ids).stream()
                .collect(Collectors.toMap(Stock::getProductId, Stock::getQuantity, (a, b) -> a));
    }

    private boolean inStock(long productId) {
        return stockRepository.findById(productId).map(s -> s.getQuantity() > 0).orElse(true);
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
    }

    /** 정렬 키를 Sort로 옮긴다. 알 수 없는 키는 신상품순으로 떨어뜨린다(500을 내지 않는다). */
    private static Sort sortOf(String sort) {
        return switch (sort == null ? "newest" : sort) {
            case "price_asc" -> Sort.by(Sort.Order.asc("price"), Sort.Order.asc("productId"));
            case "price_desc" -> Sort.by(Sort.Order.desc("price"), Sort.Order.asc("productId"));
            case "name" -> Sort.by(Sort.Order.asc("name"), Sort.Order.asc("productId"));
            default -> Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("productId"));
        };
    }
}
