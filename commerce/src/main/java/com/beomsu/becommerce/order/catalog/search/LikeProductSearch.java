package com.beomsu.becommerce.order.catalog.search;

import com.beomsu.becommerce.order.catalog.Product;
import com.beomsu.becommerce.order.catalog.ProductRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * DB {@code LIKE '%q%'} 검색. 관련도가 없어 신상품순이다.
 *
 * <ul>
 *   <li>{@code like} — 지금까지 나가던 것. 상품명·브랜드만 본다</li>
 *   <li>{@code like-fields} — 필드만 넓힌 것. 상품 종류·설명까지 본다. <b>엔진이 이긴 폭이 "필드를 넓힌 것"에서
 *       왔는지 "매칭 기술"에서 왔는지 가르려고</b> 둔 비교 대상이고, 엔진이 실패할 때 물러설 자리다</li>
 * </ul>
 *
 * <p>앞에 {@code %} 가 붙으므로 B-Tree 인덱스를 타지 못한다 — 행 수에 비례해 느려진다.
 */
public class LikeProductSearch implements ProductSearch {

    static final Sort NEWEST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("productId"));

    private final ProductRepository products;
    private final boolean allFields;

    public LikeProductSearch(ProductRepository products, boolean allFields) {
        this.products = products;
        this.allFields = allFields;
    }

    @Override
    public SearchPage search(String query, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, NEWEST);
        if (allFields) {
            Page<Long> ids = products.searchIdsAcrossFields(query, pageable);
            return new SearchPage(ids.getContent(), ids.getTotalElements());
        }
        Page<Product> result = products.findByNameContainingOrBrandContaining(query, query, pageable);
        return new SearchPage(result.map(Product::getProductId).getContent(), result.getTotalElements());
    }

    /**
     * 사용자가 고른 정렬(가격순 등)로 한 페이지. 지금까지 나가던 동작 그대로다 — 일치하는 <b>전체</b> 안에서
     * 정렬한다. 관련도 엔진은 후보를 먼저 자르므로 이 경로를 쓰지 않는다.
     */
    public Page<Product> searchSorted(String query, Pageable pageable) {
        if (!allFields) {
            return products.findByNameContainingOrBrandContaining(query, query, pageable);
        }
        Page<Long> ids = products.searchIdsAcrossFields(query, pageable);
        Map<Long, Product> byId = products.findAllById(ids.getContent()).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity()));
        List<Product> ordered = ids.getContent().stream().map(byId::get).filter(Objects::nonNull).toList();
        return new PageImpl<>(ordered, pageable, ids.getTotalElements());
    }

    @Override
    public String engine() {
        return allFields ? "like-fields" : "like";
    }

    @Override
    public boolean ranksByRelevance() {
        return false;
    }
}
