package com.beomsu.becommerce.order.catalog.search;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 색인할 상품 행을 DB 에서 읽는다. 전체 재색인과 변경 반영(#246)이 같은 SQL 을 쓴다.
 *
 * <p>재고는 {@code stock} 에 행이 없으면 있음으로 본다(주문 경로와 같은 규칙).
 */
public class CatalogDocs {

    private static final String SELECT = "SELECT p.product_id, p.name, p.product_type, p.description, p.category_code, "
            + "p.subcategory_code, p.colour_code, p.price, COALESCE(s.quantity, 1) > 0 "
            + "FROM products p LEFT JOIN stock s ON s.product_id = p.product_id";

    private static final RowMapper<LuceneProductSearch.Doc> ROW = (rs, i) -> new LuceneProductSearch.Doc(
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getString(6), rs.getString(7), rs.getLong(8), rs.getBoolean(9));

    private final NamedParameterJdbcTemplate jdbc;

    public CatalogDocs(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<LuceneProductSearch.Doc> all() {
        return jdbc.getJdbcTemplate().query(SELECT, ROW);
    }

    /** 지금 DB 에 있는 행만 돌려준다. 빠진 id 는 지워진 상품이다. */
    public List<LuceneProductSearch.Doc> byIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.query(SELECT + " WHERE p.product_id IN (:ids)", Map.of("ids", ids), ROW);
    }
}
