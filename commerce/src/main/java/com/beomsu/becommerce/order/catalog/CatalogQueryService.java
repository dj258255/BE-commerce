package com.beomsu.becommerce.order.catalog;

import com.beomsu.becommerce.order.internal.OrderException;
import com.beomsu.becommerce.order.catalog.search.LikeProductSearch;
import com.beomsu.becommerce.order.catalog.search.ProductSearch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /** 상세 응답에 싣는 리뷰 수 상한 — 응답이 무한히 커지지 않게 한다(#168). */
    private static final int REVIEW_LIMIT = 20;

    /** 관련도 엔진 결과를 사용자 정렬로 다시 자를 때 쓰는 후보 수(#236). */
    private static final int SORT_CANDIDATES = 500;

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final StockRepository stockRepository;
    private final ProductReviewRepository reviewRepository;
    private final FacetCache facetCache;
    private final ProductSearch productSearch;

    public CatalogQueryService(ProductRepository productRepository,
                               CategoryRepository categoryRepository,
                               StockRepository stockRepository,
                               ProductReviewRepository reviewRepository,
                               FacetCache facetCache,
                               ProductSearch productSearch) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.stockRepository = stockRepository;
        this.reviewRepository = reviewRepository;
        this.facetCache = facetCache;
        this.productSearch = productSearch;
    }

    /**
     * 대분류 목록 — 노출 순서대로, 상품 수를 함께 센다.
     *
     * <p>대분류의 상품 수는 {@code category_code}로 한 번에 세도 **중분류 합과 같다** — 중분류가
     * 대분류를 빠짐없이 나누기 때문이다(모든 상품에 중분류가 있다). 그래서 자식을 더하지 않는다.
     */
    public List<CategoryView> categories() {
        return categoryRepository.findByParentCodeIsNullOrderBySortOrderAsc().stream()
                .map(this::view)
                .toList();
    }

    /**
     * 카테고리 트리 — 대분류와 그 중분류를 **부모 다음에 자식들** 순서로 편다.
     *
     * <p>화면이 한 번의 요청으로 사이드바를 그리게 하려는 것이다. 전체가 77행(대분류 5 + 중분류 72)이라
     * 평평하게 내려도 작다. 정렬은 대분류가 전체 순서, 중분류가 같은 부모 안에서의 순서다.
     */
    public List<CategoryView> categoryTree() {
        List<Category> all = categoryRepository.findAllByOrderBySortOrderAsc();
        Map<String, List<Category>> childrenOf = all.stream()
                .filter(c -> c.getParentCode() != null)
                .collect(Collectors.groupingBy(Category::getParentCode, LinkedHashMap::new, Collectors.toList()));
        List<CategoryView> flat = new ArrayList<>();
        for (Category parent : all) {
            if (parent.getParentCode() != null) {
                continue;
            }
            flat.add(view(parent));
            childrenOf.getOrDefault(parent.getCode(), List.of()).forEach(child -> flat.add(view(child)));
        }
        return flat;
    }

    /** 대분류면 자기 상품 수(중분류 합), 중분류면 자기 상품 수. */
    private CategoryView view(Category category) {
        long count = category.getParentCode() == null
                ? productRepository.countByCategoryCode(category.getCode())
                : productRepository.countBySubcategoryCode(category.getCode());
        return CategoryView.of(category, count);
    }

    /** 이 코드가 중분류인가. 대분류거나 모르는 코드면 false → 대분류 필터로 떨어진다. */
    private boolean isSubcategory(String code) {
        return categoryRepository.findById(code).map(c -> c.getParentCode() != null).orElse(false);
    }

    /**
     * 상품 목록 — 검색어·카테고리·추천·색상·종류·가격 범위로 좁히고 정렬·페이지네이션을 적용한다.
     * 검색어가 있으면 <b>다른 모든 필터보다 우선</b>한다(검색 화면이 쓰는 경로다).
     *
     * <p>나머지 필터는 하나의 질의로 내려간다(리포지토리 {@link ProductRepository#search}). null인
     * 필터는 조건에서 빠지므로 조합마다 메서드를 만들지 않는다.
     *
     * <p>{@code category}는 **대분류와 중분류를 모두 받는다**. 파라미터를 둘로 나누면
     * {@code ?category=ladieswear&subcategory=menswear.knitwear} 같은 모순된 조합이 만들어지고
     * 그걸 검증할 에러 케이스가 늘어난다. 코드 하나로 해석하는 편이 상태가 하나다.
     * 대분류 코드는 {@code categoryCode}로, 중분류 코드는 {@code subcategoryCode}로 내려간다.
     */
    public ProductPageView products(String category, String q, Boolean featured,
                                    String colour, String productType,
                                    Long minPrice, Long maxPrice, String sort, int page, int size) {
        if (q != null && !q.isBlank()) {
            return search(q.trim(), sort, Math.max(page, 0), clampSize(size));
        }
        Pageable pageable = PageRequest.of(Math.max(page, 0), clampSize(size), sortOf(sort));
        String[] axis = categoryAxis(category);
        Page<Product> result = productRepository.search(axis[0], axis[1], blankToNull(colour),
                blankToNull(productType), minPrice, maxPrice, featured, pageable);
        return toPageView(result);
    }

    /**
     * 검색어 경로(#236, ADR-051). 어느 구현이 답하든 <b>id 와 순서만</b> 받고 상품·재고는 DB 에서 채운다.
     *
     * <ul>
     *   <li>정렬을 고르지 않았으면({@code null}·{@code relevance}) 검색 구현의 순서를 그대로 쓴다.
     *       관련도 엔진이면 관련도순, LIKE 면 신상품순이다</li>
     *   <li>정렬을 골랐으면 LIKE 는 일치하는 전체 안에서 정렬하고(지금까지의 동작), 관련도 엔진은 상위
     *       {@value #SORT_CANDIDATES}개 후보 안에서 정렬한다 — "검색 결과 안에서 가격순"이다</li>
     * </ul>
     */
    private ProductPageView search(String keyword, String sort, int page, int size) {
        if (sort == null || sort.isBlank() || "relevance".equals(sort)) {
            ProductSearch.SearchPage hits = productSearch.search(keyword, page, size);
            return toPageView(hydrate(hits, PageRequest.of(page, size)));
        }
        Pageable pageable = PageRequest.of(page, size, sortOf(sort));
        if (productSearch instanceof LikeProductSearch like) {
            return toPageView(like.searchSorted(keyword, pageable));
        }
        List<Long> candidates = productSearch.search(keyword, 0, SORT_CANDIDATES).ids();
        if (candidates.isEmpty()) {
            return toPageView(Page.empty(pageable));
        }
        return toPageView(productRepository.findByProductIdIn(candidates, pageable));
    }

    /** 검색 결과 순서를 지키며 상품을 채운다. 색인에는 있는데 DB 에 없는 id(삭제된 상품)는 뺀다. */
    private Page<Product> hydrate(ProductSearch.SearchPage hits, Pageable pageable) {
        Map<Long, Product> byId = productRepository.findAllById(hits.ids()).stream()
                .collect(Collectors.toMap(Product::getProductId, p -> p));
        List<Product> ordered = hits.ids().stream().map(byId::get).filter(Objects::nonNull).toList();
        return new PageImpl<>(ordered, pageable, hits.total());
    }

    /**
     * 패싯 — 필터 패널이 쓸 색상·상품 종류의 값과 개수.
     *
     * <p>각 패싯은 <b>자기 축을 뺀</b> 나머지 필터만 적용한다(색상 패싯은 색상 필터 없이, 종류 패싯은
     * 종류 필터 없이). 그래야 한 값을 고른 상태에서도 다른 값의 개수가 그대로 보인다. 검색어({@code q})는
     * 패싯에 적용하지 않는다 — 검색 화면은 별도 페이지다.
     */
    public FacetView facets(String category, Boolean featured, String colour, String productType,
                            Long minPrice, Long maxPrice) {
        String[] axis = categoryAxis(category);
        // **요청마다 세지 않는다**(ADR-044). 패싯은 카탈로그가 바뀔 때만 달라지는데, 실측에서
        // 패싯이 페이지 지연의 82~90% 를 차지했다. 키는 필터 조합이다 — 값이 아니라 **조합**이 키다.
        String key = String.join("|", String.valueOf(axis[0]), String.valueOf(axis[1]),
                String.valueOf(blankToNull(colour)), String.valueOf(blankToNull(productType)),
                String.valueOf(minPrice), String.valueOf(maxPrice), String.valueOf(featured));
        return facetCache.get(key, () -> {
            List<FacetCount> colours = productRepository.colourFacet(axis[0], axis[1],
                    blankToNull(productType), minPrice, maxPrice, featured);
            List<FacetCount> productTypes = productRepository.typeFacet(axis[0], axis[1],
                    blankToNull(colour), minPrice, maxPrice, featured);
            return new FacetView(colours, productTypes);
        });
    }

    /**
     * 카테고리 코드를 {@code [categoryCode, subcategoryCode]} 한 쌍으로 푼다.
     * 대분류면 앞자리만, 중분류면 뒷자리만 채운다(둘 다 채우면 대분류 필터가 중분류를 덮어쓴다).
     */
    private String[] categoryAxis(String category) {
        if (category == null || category.isBlank()) {
            return new String[]{null, null};
        }
        return isSubcategory(category) ? new String[]{null, category} : new String[]{category, null};
    }

    /** 빈 문자열 필터는 "없음"으로 본다 — 폼이 빈 값을 보내는 경우를 조건에서 빼기 위해서다. */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    /** 상품 상세. 없는 상품은 주문 경로와 같은 코드(PRODUCT_NOT_FOUND)로 404를 낸다. */
    public ProductDetailView product(long productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> OrderException.productNotFound(productId));
        return ProductDetailView.of(product, categoryNames().get(product.getCategoryCode()),
                inStock(product.getProductId()), reviewsOf(product.getProductId()));
    }

    /**
     * 리뷰 목록 — <b>합성임을 값으로 표시한다</b>(#168).
     *
     * <p>개수를 세어 평균을 내지 않는다. 화면은 "리뷰 4.3점"을 말하지 않고, 리뷰는 <b>읽는 것</b>으로만
     * 남는다(ADR-046). 최신순 상한을 두는 이유는 상세 응답이 무한히 커지지 않게 하기 위함이다.
     */
    private List<ProductDetailView.ReviewView> reviewsOf(long productId) {
        return reviewRepository.findByProductIdOrderByIdDesc(productId, PageRequest.of(0, REVIEW_LIMIT))
                .stream()
                .map(r -> new ProductDetailView.ReviewView(r.getSource(), r.getRating(), r.getBody(),
                        r.getAuthorLabel(), r.getCreatedAt().toString()))
                .toList();
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

    /**
     * 여러 상품의 <b>재고 보유 여부</b>만 한 번에 돌려준다(N+1 방지).
     *
     * <p>상품 행이 없으면 재고 부족의 근거가 아니므로 보유로 본다 — {@link #inStock(long)} 과 같은
     * 규칙이다. 위시리스트처럼 상품 id 묶음에서 카드 값을 만들어야 하는 다른 모듈이 쓴다.
     */
    public Set<Long> idsInStock(Collection<Long> productIds) {
        if (productIds.isEmpty()) {
            return Set.of();
        }
        Map<Long, Integer> quantities = stockRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.toMap(Stock::getProductId, Stock::getQuantity, (a, b) -> a));
        return productIds.stream()
                .filter(id -> quantities.getOrDefault(id, 1) > 0)
                .collect(Collectors.toSet());
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
