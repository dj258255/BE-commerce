package com.beomsu.becommerce.order.catalog.search;

import com.beomsu.becommerce.order.catalog.Product;
import com.beomsu.becommerce.order.catalog.ProductRepository;
import com.beomsu.becommerce.order.catalog.Stock;
import com.beomsu.becommerce.order.catalog.StockRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <b>후보 자르기</b>: 검색 엔진 상위 {@value #CANDIDATES}개를 받아 DB 값으로 거르고 센다(#244).
 *
 * <p>엔진에 필터 필드가 없어도 되고, 가격·재고를 DB 에서 읽으므로 낡지 않는다. 대가는 <b>결과를 잃는 것</b>이다 —
 * 조건에 맞는 상품이 관련도 {@value #CANDIDATES}위 밖에 있으면 보이지 않고, 패싯도 {@value #CANDIDATES}개 안에서만 센다.
 * 그 손실을 실측으로 잰다(리포트). 엔진 안 필터가 실패할 때 물러설 자리이기도 하다.
 */
public class CandidateFiltering {

    public static final int CANDIDATES = 500;

    private final ProductRepository products;
    private final StockRepository stock;

    public CandidateFiltering(ProductRepository products, StockRepository stock) {
        this.products = products;
        this.stock = stock;
    }

    /** 후보 한 행 — 필터 판정에 필요한 값만. */
    record Row(long id, String category, String subcategory, String colour, String type, long price, boolean inStock) {
        boolean passes(SearchFilters f) {
            return f.matches(category, subcategory, colour, type, price, inStock);
        }
    }

    public ProductSearch.SearchPage filter(ProductSearch text, String query, SearchFilters filters, int page, int size) {
        List<Row> passed = rows(text, query).stream().filter(r -> r.passes(filters)).toList();
        int from = Math.min(page * size, passed.size());
        int to = Math.min(from + size, passed.size());
        return new ProductSearch.SearchPage(passed.subList(from, to).stream().map(Row::id).toList(), passed.size());
    }

    public SearchFacets facets(ProductSearch text, String query, SearchFilters filters) {
        List<Row> rows = rows(text, query);
        return new SearchFacets(count(rows, filters.withoutColour(), Row::colour),
                count(rows, filters.withoutProductType(), Row::type));
    }

    private static Map<String, Long> count(List<Row> rows, SearchFilters f, Function<Row, String> axis) {
        Map<String, Long> counts = new LinkedHashMap<>();
        rows.stream().filter(r -> r.passes(f)).map(axis).filter(v -> v != null)
                .forEach(v -> counts.merge(v, 1L, Long::sum));
        return counts;
    }

    /** 엔진 순서를 지키며 후보의 필터 값을 DB 에서 채운다. */
    List<Row> rows(ProductSearch text, String query) {
        List<Long> ids = text.search(query, 0, CANDIDATES).ids();
        if (ids.isEmpty()) {
            return List.of();
        }
        Map<Long, Product> byId = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getProductId, Function.identity()));
        Map<Long, Integer> qty = stock.findByProductIdIn(ids).stream()
                .collect(Collectors.toMap(Stock::getProductId, Stock::getQuantity, (a, b) -> a));
        List<Row> rows = new ArrayList<>();
        for (Long id : ids) {
            Product p = byId.get(id);
            if (p != null) {
                rows.add(new Row(id, p.getCategoryCode(), p.getSubcategoryCode(), p.getColourCode(), p.getProductType(),
                        p.getPrice(), qty.getOrDefault(id, 1) > 0));
            }
        }
        return rows;
    }
}
